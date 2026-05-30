package com.blue.learnjp.http;

import com.blue.learnjp.config.CircuitBreakerConfig;
import com.blue.learnjp.config.RetryConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientCallExecutorTests {

    @Test
    void retriesRetryableFailuresUntilSuccess() {
        ResilientCallExecutor executor = new ResilientCallExecutor(
            "test-service",
            RetryConfig.defaults(3, 1, 2, 2.0),
            CircuitBreakerConfig.defaults(10, 2, 50.0, 1_000),
            new SimpleMeterRegistry()
        );
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute("operation", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RetryableExternalServiceException("temporary failure", false);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void opensCircuitBreakerAfterRepeatedFailures() {
        ResilientCallExecutor executor = new ResilientCallExecutor(
            "test-service",
            RetryConfig.defaults(1, 1, 1, 1.0),
            CircuitBreakerConfig.defaults(2, 2, 50.0, 10_000),
            new SimpleMeterRegistry()
        );
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> executor.execute("operation", () -> {
                calls.incrementAndGet();
                throw new RetryableExternalServiceException("down", false);
            })).isInstanceOf(RetryableExternalServiceException.class);
        }

        assertThatThrownBy(() -> executor.execute("operation", () -> {
            calls.incrementAndGet();
            return "should-not-run";
        })).isInstanceOf(CircuitBreakerOpenException.class);
        assertThat(calls.get()).isEqualTo(2);
    }
}
