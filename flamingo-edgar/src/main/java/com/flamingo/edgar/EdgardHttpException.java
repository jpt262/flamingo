package com.flamingo.edgar;

/** Non-retryable terminal EDGAR HTTP failure (status + context preserved). */
public class EdgardHttpException extends RuntimeException {
    private final int statusCode;

    public EdgardHttpException(String url, int statusCode, String detail) {
        super("EDGAR request failed [%d] %s — %s".formatted(statusCode, url, detail));
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
