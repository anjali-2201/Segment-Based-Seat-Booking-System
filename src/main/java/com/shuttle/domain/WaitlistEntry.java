package com.shuttle.domain;

import java.util.Objects;

/**
 * An entry in a per-segment waitlist queue.
 *
 * Each entry references the Booking (by id) that is waiting for a seat on the
 * given Segment. insertionSequence is a trip-wide monotonically increasing
 * counter assigned at enqueue time, used to fairly compare entries across
 * different segment queues -- the entry with the smallest sequence number
 * (earliest overall arrival) wins promotion when multiple segments are eligible.
 *
 * No tripId field: a WaitlistEntry only ever lives inside one trip's
 * Map<Segment, Deque<WaitlistEntry>>, so the trip context is established
 * by where it is stored, not by a redundant field.
 */
public class WaitlistEntry {

    private final String bookingId;
    private final Segment segment;
    /** Trip-wide insertion counter -- smaller value means earlier arrival. */
    private final long insertionSequence;

    public WaitlistEntry(String bookingId, Segment segment, long insertionSequence) {
        this.bookingId         = Objects.requireNonNull(bookingId, "bookingId must not be null");
        this.segment           = Objects.requireNonNull(segment,   "segment must not be null");
        this.insertionSequence = insertionSequence;
    }

    public String getBookingId()       { return bookingId; }
    public Segment getSegment()        { return segment; }
    public long getInsertionSequence() { return insertionSequence; }

    @Override
    public String toString() {
        return "WaitlistEntry{bookingId='" + bookingId
                + "', segment=" + segment
                + ", seq=" + insertionSequence + "}";
    }
}