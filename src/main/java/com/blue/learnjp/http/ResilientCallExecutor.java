package com.blue.learnjp.http;

import com.blue.learnjp.config.CircuitBreakerConfig;
import com.blue.learnjp.config.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;

public class ResilientCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientCallExecutor.class);

    private final String serviceName;
    private final RetryConfig retryConfig;
    private final SimpleCircuitBreaker circuitBreaker;
    private final MeterRegistry meterRegistry;

    public ResilientCallExecutor(String serviceName,
                                 RetryConfig retryConfig,
                                 CircuitBreakerConfig circuitBreakerConfig,
                                 MeterRegistry meterRegistry) {
        this.serviceName = serviceName;
        this.retryConfig = retryConfig;
        this.circuitBreaker = new SimpleCircuitBreaker(circuitBreakerConfig);
        this.meterRegistry = meterRegistry;

        io.micrometer.core.instrument.Gauge.builder("external.http.circuit_breaker.state",
                this.circuitBreaker, SimpleCircuitBreaker::stateCode)
            .description("Circuit breaker state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN")
            .tag("service", serviceName)
            .register(meterRegistry);
    }

    public <T> T execute(String operation, Callable<T> callable) {
        Timer.Sample totalSample = Timer.start(meterRegistry);

        if (!circuitBreaker.tryAcquirePermission()) {
            Counter.builder("external.http.circuit_breaker.rejections")
                .tags(tags(operation))
                .register(meterRegistry)
                .increment();
            totalSample.stop(timer(operation, "circuit_open"));
            throw new CircuitBreakerOpenException(serviceName + " circuit breaker is OPEN for " + operation);
        }

        int retriesPerformed = 0;
        Throwable lastFailure = null;

        for (int attempt = 1; attempt <= retryConfig.maxAttempts(); attempt++) {
            try {
                T result = callable.call();
                circuitBreaker.onSuccess();
                Counter.builder("external.http.calls")
                    .tags(tags(operation).and("outcome", "success"))
                    .register(meterRegistry)
                    .increment();
                totalSample.stop(timer(operation, "success"));
                return result;
            } catch (Throwable failure) {
                lastFailure = failure;
                boolean retryable = isRetryable(failure);
                boolean timeout = isTimeout(failure);

                recordFailureMetrics(operation, retryable, timeout);

                if (!retryable || attempt >= retryConfig.maxAttempts()) {
                    circuitBreaker.onFailure();
                    totalSample.stop(timer(operation, timeout ? "timeout" : "failure"));
                    throw propagateFailure(operation, failure, retryable, timeout, retriesPerformed);
                }

                circuitBreaker.onFailure();
                retriesPerformed++;
                Counter.builder("external.http.retries")
                    .tags(tags(operation))
                    .register(meterRegistry)
                    .increment();

                long backoffMs = retryConfig.backoffForAttempt(retriesPerformed);
                log.warn("{} {} attempt {}/{} failed, retrying in {} ms: {}",
                    serviceName, operation, attempt, retryConfig.maxAttempts(), backoffMs, summary(failure));
                try {
                    sleep(backoffMs, operation);
                } catch (RetryableExternalServiceException interruptedRetry) {
                    totalSample.stop(timer(operation, "failure"));
                    throw interruptedRetry;
                }
            }
        }

        totalSample.stop(timer(operation, "failure"));
        throw propagateFailure(operation, lastFailure, true, isTimeout(lastFailure), retriesPerformed);
    }

    private void recordFailureMetrics(String operation, boolean retryable, boolean timeout) {
        Counter.builder("external.http.calls")
            .tags(tags(operation).and("outcome", timeout ? "timeout" : "failure"))
            .register(meterRegistry)
            .increment();

        if (timeout) {
            Counter.builder("external.http.timeouts")
                .tags(tags(operation))
                .register(meterRegistry)
                .increment();
        }

        if (!retryable) {
            Counter.builder("external.http.non_retryable_failures")
                .tags(tags(operation))
                .register(meterRegistry)
                .increment();
        }
    }

    private RuntimeException propagateFailure(String operation, Throwable failure,
                                              boolean retryable, boolean timeout, int retriesPerformed) {
        String message = String.format("%s %s failed after %d attempt(s): %s",
            serviceName, operation, retriesPerformed + 1, summary(failure));

        if (failure instanceof CircuitBreakerOpenException exception) {
            return exception;
        }
        if (failure instanceof RuntimeException runtime && !retryable) {
            return runtime;
        }
        if (failure instanceof RuntimeException runtime && timeout && runtime instanceof RetryableExternalServiceException) {
            return runtime;
        }
        if (failure instanceof com.blue.learnjp.service.RateLimitException rateLimitException) {
            return rateLimitException;
        }
        if (failure instanceof RetryableExternalServiceException retryableFailure) {
            return retryableFailure;
        }
        if (failure instanceof RuntimeException runtime && retryable) {
            return new RetryableExternalServiceException(message, runtime, timeout);
        }
        return new RetryableExternalServiceException(message, failure, timeout);
    }

    private void sleep(long backoffMs, String operation) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RetryableExternalServiceException(
                serviceName + " " + operation + " retry interrupted",
                interruptedException,
                false
            );
        }
    }

    private boolean isRetryable(Throwable failure) {
        return failure instanceof com.blue.learnjp.service.RateLimitException
            || failure instanceof RetryableExternalServiceException
            || failure instanceof java.io.IOException;
    }

    private boolean isTimeout(Throwable failure) {
        if (failure == null) {
            return false;
        }
        if (failure instanceof RetryableExternalServiceException retryableFailure) {
            return retryableFailure.timeout();
        }

        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof InterruptedIOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String summary(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        return message != null && !message.isBlank() ? message : failure.getClass().getSimpleName();
    }

    private Timer timer(String operation, String outcome) {
        return Timer.builder("external.http.latency")
            .description("External HTTP call latency including retries")
            .tags(tags(operation).and("outcome", outcome))
            .register(meterRegistry);
    }

    private Tags tags(String operation) {
        return Tags.of("service", serviceName, "operation", operation);
    }
}
