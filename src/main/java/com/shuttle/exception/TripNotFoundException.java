package com.shuttle.exception;

public class TripNotFoundException extends ShuttleBookingException {
    public TripNotFoundException(String tripId) {
        super("Trip not found: " + tripId);
    }
}