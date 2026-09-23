package com.shuttle.domain;

import java.util.TreeMap;

/**
 * Represents one physical seat on a trip.
 *
 * Bookings are tracked in a TreeMap<Integer, Booking> keyed by the booking's
 * fromIdx (start stop index). The sorted structure gives O(log k) neighbour
 * navigation via floorEntry/ceilingEntry, avoiding a full O(k) scan of all k
 * bookings per availability query (k is bounded by the number of route stops).
 *
 * This class is responsible only for availability coordination: looking up
 * nearby bookings and delegating the overlap decision to Segment.overlaps().
 * It never reimplements the overlap formula.
 *
 * Phase 1: skeleton only -- isFree() and mutation methods will be added in
 * Phase 2 once the overlap logic tests pass.
 */
public class Seat {

    private final int seatNumber;

    /**
     * Active bookings on this seat, keyed by segment start index.
     * Only CONFIRMED (and NO_SHOW, which are never removed) bookings live here.
     * WAITLISTED bookings are not in this map -- they have no seat assigned.
     */
    private final TreeMap<Integer, Booking> bookings;

    public Seat(int seatNumber) {
        if (seatNumber < 1) {
            throw new IllegalArgumentException("seatNumber must be >= 1");
        }
        this.seatNumber = seatNumber;
        this.bookings   = new TreeMap<>();
    }

    public int getSeatNumber() { return seatNumber; }

    /**
     * Package-private: raw booking map for use by the service layer.
     * Callers must not mutate this map outside a synchronized(trip) block.
     */
    TreeMap<Integer, Booking> getBookings() { return bookings; }

    @Override
    public String toString() {
        return "Seat{number=" + seatNumber + ", bookings=" + bookings.size() + "}";
    }
}