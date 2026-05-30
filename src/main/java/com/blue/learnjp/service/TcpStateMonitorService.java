package com.blue.learnjp.service;

import com.blue.learnjp.config.TcpStateMonitorConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@ConditionalOnProperty(name = "app.network.tcp-monitor.enabled", havingValue = "true", matchIfMissing = true)
public class TcpStateMonitorService {

    private static final Logger log = LoggerFactory.getLogger(TcpStateMonitorService.class);

    private final TcpStateMonitorConfig config;
    private final AtomicInteger timeWait = new AtomicInteger();
    private final AtomicInteger established = new AtomicInteger();
    private final AtomicInteger closeWait = new AtomicInteger();
    private final AtomicInteger ephemeralFirst = new AtomicInteger(-1);
    private final AtomicInteger ephemeralLast = new AtomicInteger(-1);

    public TcpStateMonitorService(TcpStateMonitorConfig config, MeterRegistry meterRegistry) {
        this.config = config;

        Gauge.builder("app.network.tcp.connections", timeWait, AtomicInteger::get)
            .tag("state", "TIME_WAIT")
            .register(meterRegistry);
        Gauge.builder("app.network.tcp.connections", established, AtomicInteger::get)
            .tag("state", "ESTABLISHED")
            .register(meterRegistry);
        Gauge.builder("app.network.tcp.connections", closeWait, AtomicInteger::get)
            .tag("state", "CLOSE_WAIT")
            .register(meterRegistry);
        Gauge.builder("app.network.tcp.ephemeral.utilization", this, TcpStateMonitorService::timeWaitUtilization)
            .description("TIME_WAIT utilization ratio of the local ephemeral port range")
            .register(meterRegistry);
    }

    @Scheduled(
        fixedDelayString = "${app.network.tcp-monitor.sample-interval-ms:30000}",
        initialDelayString = "${app.network.tcp-monitor.initial-delay-ms:5000}"
    )
    public void sample() {
        Map<String, Integer> states = readTcpStates();
        timeWait.set(states.getOrDefault("TIME_WAIT", 0));
        established.set(states.getOrDefault("ESTABLISHED", 0));
        closeWait.set(states.getOrDefault("CLOSE_WAIT", 0));

        if (ephemeralFirst.get() < 0 || ephemeralLast.get() < 0) {
            detectEphemeralRange();
        }

        int threshold = warningThreshold();
        if (threshold > 0 && timeWait.get() >= threshold) {
            log.warn("TIME_WAIT count is high: {} (ESTABLISHED={}, CLOSE_WAIT={}, ephemeralRange={}~{}, threshold={})",
                timeWait.get(), established.get(), closeWait.get(),
                ephemeralFirst.get(), ephemeralLast.get(), threshold);
        }
    }

    private Map<String, Integer> readTcpStates() {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        ProcessBuilder builder = mac
            ? new ProcessBuilder("netstat", "-anp", "tcp")
            : new ProcessBuilder("netstat", "-ant");

        Map<String, Integer> counts = new HashMap<>();
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] tokens = line.trim().split("\\s+");
                    if (tokens.length == 0) {
                        continue;
                    }
                    String candidate = tokens[tokens.length - 1];
                    if (candidate.matches("[A-Z_]+")) {
                        counts.merge(candidate, 1, Integer::sum);
                    }
                }
            }
            process.waitFor();
            return counts;
        } catch (Exception e) {
            log.debug("Failed to sample TCP states: {}", e.getMessage());
            return counts;
        }
    }

    private void detectEphemeralRange() {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        try {
            if (mac) {
                ephemeralFirst.set(Integer.parseInt(runAndReadFirstLine("sysctl", "-n", "net.inet.ip.portrange.first")));
                ephemeralLast.set(Integer.parseInt(runAndReadFirstLine("sysctl", "-n", "net.inet.ip.portrange.last")));
                return;
            }

            String range = Files.readString(Path.of("/proc/sys/net/ipv4/ip_local_port_range")).trim();
            String[] tokens = range.split("\\s+");
            if (tokens.length == 2) {
                ephemeralFirst.set(Integer.parseInt(tokens[0]));
                ephemeralLast.set(Integer.parseInt(tokens[1]));
            }
        } catch (Exception e) {
            log.debug("Failed to detect ephemeral port range: {}", e.getMessage());
        }
    }

    private String runAndReadFirstLine(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line = reader.readLine();
            process.waitFor();
            return line != null ? line.trim() : "";
        }
    }

    private int warningThreshold() {
        int first = ephemeralFirst.get();
        int last = ephemeralLast.get();
        if (first > 0 && last >= first) {
            int size = (last - first) + 1;
            return Math.max(config.warnThresholdCount(), (int) Math.round(size * config.warnThresholdRatio()));
        }
        return config.warnThresholdCount();
    }

    private double timeWaitUtilization() {
        int first = ephemeralFirst.get();
        int last = ephemeralLast.get();
        if (first <= 0 || last < first) {
            return 0.0;
        }
        int size = (last - first) + 1;
        return size == 0 ? 0.0 : timeWait.get() / (double) size;
    }
}
