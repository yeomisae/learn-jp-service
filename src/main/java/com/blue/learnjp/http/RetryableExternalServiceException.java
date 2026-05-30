package com.blue.learnjp.http;

public class RetryableExternalServiceException extends RuntimeException {

    private final boolean timeout;

    public RetryableExternalServiceException(String message, Throwable cause, boolean timeout) {
        super(message, cause);
        this.timeout = timeout;
    }

    public RetryableExternalServiceException(String message, boolean timeout) {
        super(message);
        this.timeout = timeout;
    }

    public boolean timeout() {
        return timeout;
    }
}
