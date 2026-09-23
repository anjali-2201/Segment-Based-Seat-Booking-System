package com.shuttle.domain;

import java.util.Map;
import java.util.TreeMap;

/**
 * Represents one physical seat on a trip.
 *
 * Bookings are tracked in a TreeMap<Integer, Booking> keyed by the booking's
 * fromIdx (start stop index).  The sorted structure gives O(log k) neighbour
 * navigation via floorEntry/ceilingEntry, avoiding a full O(k) scan of all k
 * bookings per availability query (k is bounded by number of route stops, typically < 15).
 *
 * This class is responsible ONLY for availability coordination: looking up
 * nearby bookings and delegating the overlap decision to Segment.overlaps().
 * The overlap formula is NEVER reimplemented here.
 *
 * Complexity of isFree(): O(log k) per call.
 *   - Two TreeMap navigations (floorEntry, ceilingEntry): O(log k) each.
 *   - Two overlaps() calls: O(1) each.
 *   - Only the 1-2 nearest neighbours are ever inspected -- never the full list.
 *   This is correct because existing bookings on a seat are already non-overlapping
 *   (invariant maintained by addBooking), so only the immediate floor/ceiling
 *   neighbour can possibly conflict with a new request.
 */
public class Seat {

    private final int seatNumber;

    /**
     * Active (CONFIRMED and NO_SHOW) bookings on this seat, keyed by fromIdx.
     * WAITLISTED bookings are NOT stored here -- they have no seat assignment.
     * Mutations must happen inside a synchronized(trip) block in the service layer.
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
     * Returns true if this seat has no booking that overlaps the requested segment.
     *
     * O(log k): only the floor and ceiling neighbours in the TreeMap are inspected.
     * Correctness relies on the invariant that existing bookings on a seat are
     * mutually non-overlapping -- so only the immediate neighbours can conflict.
     *
     * @param requested the segment the caller wants to book
     */
    public boolean isFree(Segment requested) {
        // Booking that starts at or just before requested.fromIdx
        Map.Entry<Integer, Booking> floor = bookings.floorEntry(requested.getFromIdx());
        if (floor != null && floor.getValue().getSegment().overlaps(requested)) {
            return false;
        }
        // Booking that starts at or just after requested.fromIdx
        Map.Entry<Integer, Booking> ceil = bookings.ceilingEntry(requested.getFromIdx());
        if (ceil != null && ceil.getValue().getSegment().overlaps(requested)) {
            return false;
        }
        return true;
    }

    /**
     * Records a CONFIRMED (or NO_SHOW) booking on this seat.
     * Must be called inside synchronized(trip).
     */
    public void addBooking(Booking booking) {
        bookings.put(booking.getSegment().getFromIdx(), booking);
    }

    /**
     * Removes the booking for the given segment from this seat.
     * Called on cancellation (CONFIRMED -> CANCELLED).
     * Must be called inside synchronized(trip).
     *
     * @param segment the segment whose booking is being removed
     */
    public void removeBooking(Segment segment) {
        bookings.remove(segment.getFromIdx());
    }

    /** Package-private: raw booking map for use by the service layer. */
    TreeMap<Integer, Booking> getBookings() { return bookings; }

    @Override
    public String toString() {
        return "Seat{number=" + seatNumber + ", bookings=" + bookings.size() + "}";
    }
}