package com.shuttle.service;

import com.shuttle.domain.Booking;
import com.shuttle.domain.Segment;
import com.shuttle.domain.Trip;
import com.shuttle.domain.WaitlistEntry;

import java.util.Deque;
import java.util.Map;

/**
 * Manages the per-segment FIFO waitlist for a trip.
 *
 * Design (blueprint sections 4, 10):
 *
 *   ENQUEUE -- when no seat is available, a WAITLISTED Booking is registered and
 *   a WaitlistEntry (bookingId + segment + trip-wide sequence) is appended to the
 *   tail of that segment's Deque.  Two passengers requesting the IDENTICAL segment
 *   share one Deque and are strictly FIFO against each other.
 *
 *   PROMOTE -- called at most once per cancellation (Phase 5).
 *   Algorithm:
 *     For every non-empty segment deque, peek the head WaitlistEntry.
 *     Check whether that segment now fits on any seat (via Seat.isFree).
 *     Among all eligible heads, pick the one with the smallest insertionSequence
 *     (= earliest overall arrival across all segment queues).
 *     Promote it: assign the free seat, flip WAITLISTED -> CONFIRMED, dequeue.
 *   At most ONE entry is promoted per call -- bounds the critical-section work and
 *   matches "one seat freed -> at most one seat re-booked" (blueprint section 10).
 *
 * Threading: every method must be called inside synchronized(trip) by the caller.
 * WaitlistService itself performs no locking.
 */
public class WaitlistService {

    /**
     * Appends a WaitlistEntry for the given WAITLISTED booking to the tail of
     * its segment's deque, creating the deque if this is the first request for
     * that exact segment.
     *
     * Must be called inside synchronized(trip).
     */
    public void enqueue(Trip trip, Booking booking) {
        long seq            = trip.nextWaitlistSequence();
        Segment segment     = booking.getSegment();
        WaitlistEntry entry = new WaitlistEntry(booking.getBookingId(), segment, seq);
        trip.getOrCreateWaitlistDeque(segment).addLast(entry);
    }

    /**
     * Attempts to promote at most one waitlisted passenger now that a seat has
     * been freed.  Returns the promoted Booking, or null if no eligible entry
     * exists.
     *
     * Must be called inside synchronized(trip).
     *
     * Complexity: O(S * seats * log k) where S = distinct non-empty segment queues.
     * Only each queue's head is ever inspected -- never the whole queue.
     */
    public Booking promoteOne(Trip trip) {
        WaitlistEntry winner     = null;
        int           winningSeat = -1;

        // Inspect only the HEAD of each non-empty segment deque.
        for (Map.Entry<Segment, Deque<WaitlistEntry>> mapEntry
                : trip.getWaitlistBySegment().entrySet()) {

            Deque<WaitlistEntry> deque = mapEntry.getValue();
            if (deque.isEmpty()) continue;

            WaitlistEntry head = deque.peekFirst();
            int freeSeat = findFreeSeat(trip, head.getSegment());
            if (freeSeat == -1) continue;   // this segment still has no room

            // Earlier global sequence = higher priority across different segment queues
            if (winner == null
                    || head.getInsertionSequence() < winner.getInsertionSequence()) {
                winner      = head;
                winningSeat = freeSeat;
            }
        }

        if (winner == null) return null;    // nothing to promote

        // Remove the winner from the front of its segment's deque
        trip.getWaitlistBySegment().get(winner.getSegment()).pollFirst();

        // Promote: look up the Booking by id, assign seat, flip WAITLISTED -> CONFIRMED.
        // No new Booking object is created -- the existing one is mutated in place.
        Booking booking = trip.getBookings().get(winner.getBookingId());
        booking.confirm(winningSeat);
        trip.getSeat(winningSeat).addBooking(booking);

        return booking;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Returns the first free 1-based seat number for segment, or -1 if none. */
    private int findFreeSeat(Trip trip, Segment segment) {
        for (int i = 1; i <= trip.getTotalSeats(); i++) {
            if (trip.getSeat(i).isFree(segment)) return i;
        }
        return -1;
    }
}