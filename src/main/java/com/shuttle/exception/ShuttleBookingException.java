package com.shuttle.exception;

/**
 * Base unchecked exception for all shuttle-booking domain errors.
 * Callers can catch this single type for uniform error handling.
 */
public class ShuttleBookingException extends RuntimeException {
    public ShuttleBookingException(String message) {
        super(message);
    }
    public ShuttleBookingException(String message, Throwable cause) {
        super(message, cause);
    }
}