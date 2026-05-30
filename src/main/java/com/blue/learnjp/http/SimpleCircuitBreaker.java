package com.blue.learnjp.http;

import com.blue.learnjp.config.CircuitBreakerConfig;

import java.util.ArrayDeque;
import java.util.Deque;

public class SimpleCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final CircuitBreakerConfig config;
    private final Deque<Boolean> recentCalls = new ArrayDeque<>();

    private State state = State.CLOSED;
    private long openedAtMillis = -1;
    private boolean halfOpenProbeInProgress = false;

    public SimpleCircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
    }

    public synchronized boolean tryAcquirePermission() {
        long now = System.currentTimeMillis();

        if (state == State.OPEN) {
            if (now - openedAtMillis < config.openDurationMs()) {
                return false;
            }
            state = State.HALF_OPEN;
            halfOpenProbeInProgress = false;
        }

        if (state == State.HALF_OPEN) {
            if (halfOpenProbeInProgress) {
                return false;
            }
            halfOpenProbeInProgress = true;
            return true;
        }

        return true;
    }

    public synchronized void onSuccess() {
        if (state == State.HALF_OPEN) {
            close();
            return;
        }

        record(true);
    }

    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            open();
            return;
        }

        record(false);
        if (recentCalls.size() < config.minimumNumberOfCalls()) {
            return;
        }

        long failures = recentCalls.stream().filter(success -> !success).count();
        double failureRate = (failures * 100.0) / recentCalls.size();
        if (failureRate >= config.failureRateThreshold()) {
            open();
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int stateCode() {
        return switch (state) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
        };
    }

    private void record(boolean success) {
        if (recentCalls.size() == config.slidingWindowSize()) {
            recentCalls.removeFirst();
        }
        recentCalls.addLast(success);
    }

    private void open() {
        state = State.OPEN;
        openedAtMillis = System.currentTimeMillis();
        halfOpenProbeInProgress = false;
    }

    private void close() {
        state = State.CLOSED;
        openedAtMillis = -1;
        halfOpenProbeInProgress = false;
        recentCalls.clear();
    }
}
