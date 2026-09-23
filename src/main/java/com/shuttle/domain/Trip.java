package com.shuttle.domain;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A specific run of a Route on a given date, with a fixed seat count.
 *
 * Trip owns all mutable booking state for that run:
 *   seats              -- fixed-size array; O(1) lookup by seat number.
 *   bookings           -- all bookings (any status) keyed by bookingId.
 *   waitlistBySegment  -- per-segment FIFO waitlist deques.
 *   waitlistSequence   -- trip-wide counter for cross-queue fairness.
 *
 * All mutations to these fields must happen inside a synchronized(trip) block
 * in the service layer. Trip itself performs no locking -- it is the monitor
 * object, not the lock manager.
 */
public class Trip {

    private final String id;
    private final Route route;
    private final LocalDate date;

    /** seats[0] is unused; seats[i] = seat number i (1-based). */
    private final Seat[] seats;

    /**
     * All bookings for this trip, keyed by bookingId.
     * Covers CONFIRMED, WAITLISTED, CANCELLED, and NO_SHOW bookings.
     */
    private final Map<String, Booking> bookings;

    /**
     * One FIFO deque per exact requested segment.
     * Segment.equals/hashCode ensures identical segments share a single deque.
     */
    private final Map<Segment, Deque<WaitlistEntry>> waitlistBySegment;

    /**
     * Monotonically increasing counter for waitlist entries.
     * Assigned at enqueue time; smaller value = earlier arrival.
     */
    private final AtomicLong waitlistSequence;

    public Trip(String id, Route route, LocalDate date, int totalSeats) {
        this.id   = Objects.requireNonNull(id,    "id must not be null");
        this.route = Objects.requireNonNull(route, "route must not be null");
        this.date  = Objects.requireNonNull(date,  "date must not be null");
        if (totalSeats < 1) {
            throw new IllegalArgumentException("totalSeats must be >= 1");
        }
        // 1-based seat numbering: index 0 left null, seats 1..totalSeats are real.
        this.seats = new Seat[totalSeats + 1];
        for (int i = 1; i <= totalSeats; i++) {
            seats[i] = new Seat(i);
        }
        this.bookings           = new HashMap<>();
        this.waitlistBySegment  = new HashMap<>();
        this.waitlistSequence   = new AtomicLong(0);
    }

    // ---- Getters -------------------------------------------------------

    public String getId()      { return id; }
    public Route getRoute()    { return route; }
    public LocalDate getDate() { return date; }

    /** Total seat capacity (seats are numbered 1..totalSeats). */
    public int getTotalSeats() { return seats.length - 1; }

    /**
     * Returns the Seat for the given 1-based seat number,
     * or null if the number is out of range.
     */
    public Seat getSeat(int seatNumber) {
        if (seatNumber < 1 || seatNumber >= seats.length) return null;
        return seats[seatNumber];
    }

    /** Package-private: raw seat array for iteration in service layer. */
    Seat[] getSeatsArray() { return seats; }

    /** All bookings keyed by bookingId (every status). */
    public Map<String, Booking> getBookings() { return bookings; }

    /** The per-segment waitlist deque map. */
    public Map<Segment, Deque<WaitlistEntry>> getWaitlistBySegment() {
        return waitlistBySegment;
    }

    /**
     * Returns the next trip-wide waitlist sequence number and increments it.
     * Call only inside a synchronized(trip) block.
     */
    public long nextWaitlistSequence() { return waitlistSequence.getAndIncrement(); }

    /**
     * Returns the deque for the given segment, creating an empty one if absent.
     * Must be called inside synchronized(trip).
     */
    public Deque<WaitlistEntry> getOrCreateWaitlistDeque(Segment segment) {
        return waitlistBySegment.computeIfAbsent(segment, k -> new ArrayDeque<>());
    }

    @Override
    public String toString() {
        return "Trip{id='" + id + "', route=" + route.getId()
                + ", date=" + date + ", seats=" + getTotalSeats() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Trip other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}