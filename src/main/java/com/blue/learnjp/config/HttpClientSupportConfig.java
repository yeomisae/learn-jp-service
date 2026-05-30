package com.blue.learnjp.config;

import com.blue.learnjp.http.ResilientCallExecutor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientSupportConfig {

    @Bean("openClawHttpClient")
    CloseableHttpClient openClawHttpClient(OpenClawConfig config, MeterRegistry meterRegistry) {
        return buildHttpClient("openclaw", config.timeout(), config.pool(), meterRegistry);
    }

    @Bean("naverJakoHttpClient")
    CloseableHttpClient naverJakoHttpClient(NaverJakoConfig config, MeterRegistry meterRegistry) {
        return buildHttpClient("naver-jako", config.timeout(), config.pool(), meterRegistry);
    }

    @Bean("openClawCallExecutor")
    ResilientCallExecutor openClawCallExecutor(OpenClawConfig config, MeterRegistry meterRegistry) {
        return new ResilientCallExecutor("openclaw", config.retry(), config.circuitBreaker(), meterRegistry);
    }

    @Bean("naverJakoCallExecutor")
    ResilientCallExecutor naverJakoCallExecutor(NaverJakoConfig config, MeterRegistry meterRegistry) {
        return new ResilientCallExecutor("naver-jako", config.retry(), config.circuitBreaker(), meterRegistry);
    }

    private CloseableHttpClient buildHttpClient(String clientName,
                                                HttpTimeoutConfig timeoutConfig,
                                                HttpConnectionPoolConfig poolConfig,
                                                MeterRegistry meterRegistry) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(timeoutConfig.connectMs()))
                .setSocketTimeout(Timeout.ofMilliseconds(timeoutConfig.readMs()))
                .build())
            .build();
        connectionManager.setMaxTotal(poolConfig.maxTotal());
        connectionManager.setDefaultMaxPerRoute(poolConfig.maxPerRoute());
        connectionManager.setValidateAfterInactivity(TimeValue.ofMilliseconds(poolConfig.validateAfterInactivityMs()));

        registerConnectionPoolMetrics(clientName, connectionManager, meterRegistry);

        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutConfig.connectionRequestMs()))
            .setResponseTimeout(Timeout.ofMilliseconds(timeoutConfig.responseMs()))
            .build();

        return HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .disableAutomaticRetries()
            .evictExpiredConnections()
            .evictIdleConnections(TimeValue.ofMilliseconds(poolConfig.idleEvictMs()))
            .build();
    }

    private void registerConnectionPoolMetrics(String clientName,
                                               PoolingHttpClientConnectionManager connectionManager,
                                               MeterRegistry meterRegistry) {
        Gauge.builder("external.http.pool.connections", connectionManager,
                manager -> manager.getTotalStats().getLeased())
            .description("Leased HTTP client connections")
            .tag("client", clientName)
            .tag("state", "leased")
            .register(meterRegistry);
        Gauge.builder("external.http.pool.connections", connectionManager,
                manager -> manager.getTotalStats().getAvailable())
            .description("Available HTTP client connections")
            .tag("client", clientName)
            .tag("state", "available")
            .register(meterRegistry);
        Gauge.builder("external.http.pool.connections", connectionManager,
                manager -> manager.getTotalStats().getPending())
            .description("Pending HTTP client connection requests")
            .tag("client", clientName)
            .tag("state", "pending")
            .register(meterRegistry);
        Gauge.builder("external.http.pool.connections", connectionManager,
                manager -> manager.getTotalStats().getMax())
            .description("Configured max HTTP client connections")
            .tag("client", clientName)
            .tag("state", "max")
            .register(meterRegistry);
    }
}
