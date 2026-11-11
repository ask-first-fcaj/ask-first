package com.askfirst.common;

/**
 * Thrown when Telegram returns HTTP 429 Too Many Requests.
 */
public class TelegramRateLimitException extends RuntimeException {
    private final int retryAfterSeconds;

    public TelegramRateLimitException(int retryAfterSeconds, String message) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

