package com.shuttle.domain;

/**
 * Lifecycle states a Booking can be in.
 *
 * CONFIRMED   - seat assigned and active.
 * WAITLISTED  - no seat yet; passenger is queued for promotion.
 * CANCELLED   - passenger cancelled; seat has been freed.
 * NO_SHOW     - passenger did not board; seat is NOT freed mid-trip
 *               (no live clock in scope -- see README assumptions).
 */
public enum BookingStatus {
    CONFIRMED,
    WAITLISTED,
    CANCELLED,
    NO_SHOW
}