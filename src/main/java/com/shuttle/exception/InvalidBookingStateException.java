package com.shuttle.exception;

public class InvalidBookingStateException extends ShuttleBookingException {
    public InvalidBookingStateException(String message) {
        super(message);
    }
}