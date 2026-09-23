package com.shuttle.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Seat.isFree() -- verifies the O(log k) availability check using
 * all boundary cases from blueprint section 6.
 *
 * Stop layout:  A=0  B=1  C=2  D=3  E=4
 *
 * Helper: makeConfirmedBooking() creates a CONFIRMED Booking directly so we can
 * pre-populate the seat without needing BookingService (which comes in Phase 3).
 */
@DisplayName("Seat availability (isFree)")
class SeatTest {

    private Seat seat;
    private int idCounter;

    @BeforeEach
    void setUp() {
        seat      = new Seat(1);
        idCounter = 0;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Creates a minimal CONFIRMED booking and adds it to the seat. */
    private Booking occupy(int from, int to) {
        Segment seg     = new Segment(from, to);
        Booking booking = new Booking(
                "B" + (++idCounter), "T1", "P1", seg, seat.getSeatNumber(),
                BookingStatus.CONFIRMED);
        seat.addBooking(booking);
        return booking;
    }

    // ---------------------------------------------------------------
    // isFree on empty seat
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Empty seat is free for any segment")
    void emptySeat_alwaysFree() {
        assertTrue(seat.isFree(new Segment(0, 4)));
        assertTrue(seat.isFree(new Segment(1, 2)));
    }

    // ---------------------------------------------------------------
    // Blueprint section 6 boundary cases
    // ---------------------------------------------------------------

    @Test
    @DisplayName("[0,1) booked -- [1,3) is free (touching at B, not overlapping)")
    void touchAtStop_isAllowed() {
        // A->B on seat; B->D should be allowed (half-open interval: 1 <= 1 is no-overlap)
        occupy(0, 1);
        assertTrue(seat.isFree(new Segment(1, 3)),
                "B->D must be free when A->B is booked");
    }

    @Test
    @DisplayName("[1,3) booked -- [0,1) is free (touches at B from other side)")
    void touchAtStopReverse_isAllowed() {
        occupy(1, 3);
        assertTrue(seat.isFree(new Segment(0, 1)),
                "A->B must be free when B->D is booked");
    }

    @Test
    @DisplayName("[0,2) booked -- [1,3) is NOT free (A->C and B->D share B->C road)")
    void overlappingMidRoute_isRejected() {
        occupy(0, 2);  // A->C
        assertFalse(seat.isFree(new Segment(1, 3)),
                "B->D must not be free when A->C is booked");
    }

    @Test
    @DisplayName("Identical segment booked -- second identical booking is NOT free")
    void identicalSegment_isRejected() {
        occupy(1, 3);  // B->D
        assertFalse(seat.isFree(new Segment(1, 3)),
                "B->D must not be free when B->D is already booked");
    }

    @Test
    @DisplayName("Fully disjoint segments can share a seat: [0,1) then [2,4)")
    void disjointSegments_bothFit() {
        occupy(0, 1);  // A->B
        assertTrue(seat.isFree(new Segment(2, 4)),
                "C->E must be free when only A->B is booked");
    }

    @Test
    @DisplayName("Three non-overlapping segments fit on the same seat")
    void threeNonOverlappingSegments_allFit() {
        // A->B, B->C, C->E -- all touching, none overlapping
        occupy(0, 1);
        assertTrue(seat.isFree(new Segment(1, 2)));
        occupy(1, 2);
        assertTrue(seat.isFree(new Segment(2, 4)));
        occupy(2, 4);
        // Now seat is fully used; a spanning segment must be blocked
        assertFalse(seat.isFree(new Segment(0, 4)));
    }

    @Test
    @DisplayName("Containing segment rejected: [0,4) when [1,2) is booked")
    void containingSegment_isRejected() {
        occupy(1, 2);  // B->C
        assertFalse(seat.isFree(new Segment(0, 4)),
                "A->E must not be free when B->C is booked");
    }

    @Test
    @DisplayName("Contained segment rejected: [1,2) when [0,4) is booked")
    void containedSegment_isRejected() {
        occupy(0, 4);  // A->E
        assertFalse(seat.isFree(new Segment(1, 2)),
                "B->C must not be free when A->E is booked");
    }

    @Test
    @DisplayName("Partial overlap at start: [0,3) booked, [2,4) is NOT free")
    void partialOverlapAtStart_isRejected() {
        occupy(0, 3);  // A->D
        assertFalse(seat.isFree(new Segment(2, 4)));
    }

    @Test
    @DisplayName("Partial overlap at end: [2,4) booked, [0,3) is NOT free")
    void partialOverlapAtEnd_isRejected() {
        occupy(2, 4);  // C->E
        assertFalse(seat.isFree(new Segment(0, 3)));
    }

    // ---------------------------------------------------------------
    // addBooking / removeBooking
    // ---------------------------------------------------------------

    @Test
    @DisplayName("removeBooking frees the seat for that segment")
    void removeBooking_freesSegment() {
        Booking b = occupy(1, 3);  // B->D
        assertFalse(seat.isFree(new Segment(1, 3)), "Should be occupied");

        seat.removeBooking(b.getSegment());

        assertTrue(seat.isFree(new Segment(1, 3)), "Should be free after removal");
    }

    @Test
    @DisplayName("After removeBooking, previously blocked adjacent segment is still free")
    void removeBooking_doesNotAffectOtherSegments() {
        occupy(0, 1);          // A->B
        Booking b = occupy(1, 3);  // B->D
        seat.removeBooking(b.getSegment());

        // A->B is still there
        assertFalse(seat.isFree(new Segment(0, 2)),
                "A->C must still be blocked by A->B");
        // B->D is now free
        assertTrue(seat.isFree(new Segment(1, 3)));
    }

    // ---------------------------------------------------------------
    // isFree checks floor AND ceiling (not just one)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("isFree checks ceiling: segment starting after fromIdx can still conflict")
    void isFree_checksCeiling() {
        // Book [2,4) -- ceiling when we query [1,3)
        occupy(2, 4);
        assertFalse(seat.isFree(new Segment(1, 3)),
                "[1,3) must be blocked by ceiling booking [2,4)");
    }

    @Test
    @DisplayName("isFree checks floor: segment starting before fromIdx can still conflict")
    void isFree_checksFloor() {
        // Book [0,3) -- floor when we query [2,4)
        occupy(0, 3);
        assertFalse(seat.isFree(new Segment(2, 4)),
                "[2,4) must be blocked by floor booking [0,3)");
    }
}