package com.shuttle.domain;

import java.util.Objects;

/**
 * Represents a single booking for a passenger on a specific trip segment.
 *
 * A booking exists from the moment it is created, regardless of status:
 *   CONFIRMED  - seat is assigned (seatNumber is set).
 *   WAITLISTED - no seat yet; seatNumber is null.
 *   CANCELLED  - booking was cancelled; seat has been freed.
 *   NO_SHOW    - passenger did not board; seat is NOT freed (no live clock).
 *
 * All mutations (status changes, seat assignment) are done by the service layer
 * inside a synchronized(trip) block.
 */
public class Booking {

    private final String bookingId;
    private final String tripId;
    private final String passengerId;
    private final Segment segment;

    /** Null when status is WAITLISTED; set once promoted or initially confirmed. */
    private Integer seatNumber;
    private BookingStatus status;

    public Booking(String bookingId,
                   String tripId,
                   String passengerId,
                   Segment segment,
                   Integer seatNumber,
                   BookingStatus status) {
        this.bookingId   = Objects.requireNonNull(bookingId,   "bookingId must not be null");
        this.tripId      = Objects.requireNonNull(tripId,      "tripId must not be null");
        this.passengerId = Objects.requireNonNull(passengerId, "passengerId must not be null");
        this.segment     = Objects.requireNonNull(segment,     "segment must not be null");
        this.status      = Objects.requireNonNull(status,      "status must not be null");
        // seatNumber may legitimately be null for WAITLISTED bookings
        this.seatNumber  = seatNumber;
    }

    // ---- Getters -------------------------------------------------------

    public String getBookingId()     { return bookingId; }
    public String getTripId()        { return tripId; }
    public String getPassengerId()   { return passengerId; }
    public Segment getSegment()      { return segment; }
    public Integer getSeatNumber()   { return seatNumber; }
    public BookingStatus getStatus() { return status; }

    // ---- Mutators (used only by the service layer) ---------------------

    /** Promotes this booking: assigns seat and flips status to CONFIRMED. */
    public void confirm(int seatNumber) {
        this.seatNumber = seatNumber;
        this.status     = BookingStatus.CONFIRMED;
    }

    /** Marks this booking as CANCELLED. */
    public void cancel() { this.status = BookingStatus.CANCELLED; }

    /** Marks this booking as NO_SHOW. Seat is NOT freed. */
    public void markNoShow() { this.status = BookingStatus.NO_SHOW; }

    @Override
    public String toString() {
        return "Booking{id='" + bookingId
                + "', trip='" + tripId
                + "', passenger='" + passengerId
                + "', segment=" + segment
                + ", seat=" + seatNumber
                + ", status=" + status + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Booking other)) return false;
        return Objects.equals(bookingId, other.bookingId);
    }

    @Override
    public int hashCode() { return Objects.hash(bookingId); }
}