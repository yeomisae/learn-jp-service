package com.blue.learnjp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.network.tcp-monitor")
public record TcpStateMonitorConfig(
    long sampleIntervalMs,
    long initialDelayMs,
    double warnThresholdRatio,
    int warnThresholdCount
) {

    public TcpStateMonitorConfig {
        sampleIntervalMs = sampleIntervalMs > 0 ? sampleIntervalMs : 30_000;
        initialDelayMs = initialDelayMs > 0 ? initialDelayMs : 5_000;
        warnThresholdRatio = warnThresholdRatio > 0 ? warnThresholdRatio : 0.70;
        warnThresholdCount = warnThresholdCount > 0 ? warnThresholdCount : 2_000;
    }
}
