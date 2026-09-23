package com.shuttle.exception;

/**
 * Thrown when a requested booking segment is invalid:
 *   - fromStop not on the route
 *   - toStop not on the route
 *   - fromStop sequenceIndex >= toStop sequenceIndex (same stop or reversed order)
 */
public class InvalidSegmentException extends ShuttleBookingException {
    public InvalidSegmentException(String message) {
        super(message);
    }
}