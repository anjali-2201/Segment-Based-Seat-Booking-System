package com.shuttle.exception;

public class BookingNotFoundException extends ShuttleBookingException {
    public BookingNotFoundException(String bookingId) {
        super("Booking not found: " + bookingId);
    }
}