package com.example.capstone.analysis.client;

/** Safe diagnostic categories only: never retain remote payloads, URLs or credentials. */
public final class AiCallException extends RuntimeException {
    public enum Reason { CONFIGURATION, AUTHENTICATION, PROFILE, INVALID_RESPONSE, INPUT_LIMIT, TIMEOUT, UNAVAILABLE }
    private final Reason reason;
    public AiCallException(Reason reason) { super("AI call failed: " + reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
    public boolean retryable() { return reason == Reason.TIMEOUT || reason == Reason.UNAVAILABLE; }
}
