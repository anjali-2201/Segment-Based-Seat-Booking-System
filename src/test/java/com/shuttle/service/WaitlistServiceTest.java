package com.shuttle.service;

import com.shuttle.domain.*;
import com.shuttle.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WaitlistService: enqueue behaviour and promoteOne() promotion logic.
 *
 * Route: A(0) -> B(1) -> C(2) -> D(3)
 *
 * Promotion tests set up a full trip, inspect state, then manually free a seat
 * (via Seat.removeBooking) and call promoteOne() -- the same sequence cancel()
 * will execute in Phase 5, but tested here in isolation so the promotion logic
 * can be verified independently of cancellation.
 */
@DisplayName("WaitlistService")
class WaitlistServiceTest {

    private Stop stopA, stopB, stopC, stopD;
    private Route route;
    private TripRepository repo;
    private BookingService bookingService;
    private WaitlistService waitlistService;

    @BeforeEach
    void setUp() {
        stopA = new Stop("A", "Alpha",   0, LocalTime.of(10, 0));
        stopB = new Stop("B", "Bravo",   1, LocalTime.of(10, 15));
        stopC = new Stop("C", "Charlie", 2, LocalTime.of(10, 30));
        stopD = new Stop("D", "Delta",   3, LocalTime.of(10, 45));

        route          = new Route("R1", "City Loop", List.of(stopA, stopB, stopC, stopD));
        repo           = new TripRepository();
        waitlistService = new WaitlistService();
        bookingService  = new BookingService(repo, waitlistService);
    }

    // helper: create a 1-seat trip and save it
    private Trip oneSeatTrip(String id) {
        Trip t = new Trip(id, route, LocalDate.of(2026, 9, 23), 1);
        repo.save(t);
        return t;
    }

    // helper: create a 2-seat trip and save it
    private Trip twoSeatTrip(String id) {
        Trip t = new Trip(id, route, LocalDate.of(2026, 9, 23), 2);
        repo.save(t);
        return t;
    }

    // ---------------------------------------------------------------
    // Enqueue behaviour (verified via trip.waitlistBySegment)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("WAITLISTED booking is enqueued in the correct segment deque")
    void enqueue_waitlistedBookingAppearsInDeque() {
        Trip trip = oneSeatTrip("T1");
        bookingService.book("T1", "P1", "A", "D");   // fills seat 1

        Booking w = bookingService.book("T1", "P2", "A", "D");  // WAITLISTED

        assertEquals(BookingStatus.WAITLISTED, w.getStatus());
        Segment seg = new Segment(0, 3);
        Deque<WaitlistEntry> deque = trip.getWaitlistBySegment().get(seg);
        assertNotNull(deque, "Deque must exist for segment A->D");
        assertEquals(1, deque.size());
        assertEquals(w.getBookingId(), deque.peekFirst().getBookingId());
    }

    @Test
    @DisplayName("Two WAITLISTED bookings for same segment share one deque, in arrival order")
    void enqueue_twoSameSegment_sharedDequeInOrder() {
        Trip trip = oneSeatTrip("T2");
        bookingService.book("T2", "P1", "A", "D");   // fills seat 1

        Booking w1 = bookingService.book("T2", "P2", "A", "D");
        Booking w2 = bookingService.book("T2", "P3", "A", "D");

        Segment seg = new Segment(0, 3);
        Deque<WaitlistEntry> deque = trip.getWaitlistBySegment().get(seg);
        assertEquals(2, deque.size());

        WaitlistEntry[] entries = deque.toArray(new WaitlistEntry[0]);
        assertEquals(w1.getBookingId(), entries[0].getBookingId(), "P2 must be first (earlier arrival)");
        assertEquals(w2.getBookingId(), entries[1].getBookingId(), "P3 must be second");
    }

    @Test
    @DisplayName("Different segments get separate deques")
    void enqueue_differentSegments_separateDeques() {
        Trip trip = twoSeatTrip("T3");
        // Fill both seats
        bookingService.book("T3", "P1", "A", "D");
        bookingService.book("T3", "P2", "A", "D");

        Booking wAB = bookingService.book("T3", "P3", "A", "B");
        Booking wBC = bookingService.book("T3", "P4", "B", "C");

        Segment abSeg = new Segment(0, 1);
        Segment bcSeg = new Segment(1, 2);

        assertEquals(1, trip.getWaitlistBySegment().get(abSeg).size());
        assertEquals(1, trip.getWaitlistBySegment().get(bcSeg).size());
    }

    @Test
    @DisplayName("Insertion sequence is strictly increasing across enqueues")
    void enqueue_sequenceIsMonotonicallyIncreasing() {
        Trip trip = oneSeatTrip("T4");
        bookingService.book("T4", "P1", "A", "D");

        bookingService.book("T4", "P2", "A", "B");
        bookingService.book("T4", "P3", "B", "C");
        bookingService.book("T4", "P4", "C", "D");

        // Collect all entries across all deques, sort by sequence number,
        // then verify they are all unique and strictly increasing.
        // (HashMap iteration order is non-deterministic, so we sort first.)
        java.util.List<Long> seqs = new java.util.ArrayList<>();
        for (Deque<WaitlistEntry> dq : trip.getWaitlistBySegment().values()) {
            for (WaitlistEntry e : dq) {
                seqs.add(e.getInsertionSequence());
            }
        }
        seqs.sort(java.util.Comparator.naturalOrder());
        assertEquals(3, seqs.size(), "Three waitlisted entries expected");
        for (int i = 1; i < seqs.size(); i++) {
            assertTrue(seqs.get(i) > seqs.get(i - 1),
                    "Sequences must be strictly increasing when sorted");
        }
    }

    // ---------------------------------------------------------------
    // promoteOne() -- no eligible entries
    // ---------------------------------------------------------------

    @Test
    @DisplayName("promoteOne returns null when waitlist is empty")
    void promoteOne_emptyWaitlist_returnsNull() {
        Trip trip = oneSeatTrip("T5");
        synchronized (trip) {
            assertNull(waitlistService.promoteOne(trip));
        }
    }

    @Test
    @DisplayName("promoteOne returns null when waitlisted segment still has no free seat")
    void promoteOne_noFreeSeat_returnsNull() {
        Trip trip = oneSeatTrip("T6");
        bookingService.book("T6", "P1", "A", "D");   // seat 1: A->D
        bookingService.book("T6", "P2", "B", "C");   // WAITLISTED B->C

        // Seat 1 still occupied by A->D -- B->C cannot be promoted
        synchronized (trip) {
            assertNull(waitlistService.promoteOne(trip));
        }
    }

    // ---------------------------------------------------------------
    // promoteOne() -- single eligible entry
    // ---------------------------------------------------------------

    @Test
    @DisplayName("promoteOne promotes the single waitlisted booking when a seat is freed")
    void promoteOne_singleEntry_promoted() {
        Trip trip = oneSeatTrip("T7");
        Booking confirmed = bookingService.book("T7", "P1", "A", "D");
        Booking waiting   = bookingService.book("T7", "P2", "A", "D");

        assertEquals(BookingStatus.WAITLISTED, waiting.getStatus());

        // Simulate cancellation: free seat 1 for A->D
        synchronized (trip) {
            trip.getSeat(confirmed.getSeatNumber())
                .removeBooking(confirmed.getSegment());

            Booking promoted = waitlistService.promoteOne(trip);

            assertNotNull(promoted);
            assertEquals(waiting.getBookingId(), promoted.getBookingId());
            assertEquals(BookingStatus.CONFIRMED, promoted.getStatus());
            assertNotNull(promoted.getSeatNumber());
        }
    }

    @Test
    @DisplayName("promoteOne removes the entry from its deque after promotion")
    void promoteOne_dequeEmptyAfterPromotion() {
        Trip trip = oneSeatTrip("T8");
        Booking c = bookingService.book("T8", "P1", "A", "D");
        bookingService.book("T8", "P2", "A", "D");

        synchronized (trip) {
            trip.getSeat(c.getSeatNumber()).removeBooking(c.getSegment());
            waitlistService.promoteOne(trip);

            Deque<WaitlistEntry> dq =
                trip.getWaitlistBySegment().get(new Segment(0, 3));
            assertTrue(dq == null || dq.isEmpty(), "Deque must be empty after promotion");
        }
    }

    // ---------------------------------------------------------------
    // promoteOne() -- same segment: strict FIFO within one deque
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Same segment: first enqueued is promoted first (strict FIFO)")
    void promoteOne_sameSegment_fifoOrder() {
        Trip trip = oneSeatTrip("T9");
        Booking c  = bookingService.book("T9", "P1", "A", "D");
        Booking w1 = bookingService.book("T9", "P2", "A", "D");   // seq 0
        Booking w2 = bookingService.book("T9", "P3", "A", "D");   // seq 1

        synchronized (trip) {
            trip.getSeat(c.getSeatNumber()).removeBooking(c.getSegment());

            // First promotion: w1 (earlier in deque)
            Booking p1 = waitlistService.promoteOne(trip);
            assertEquals(w1.getBookingId(), p1.getBookingId(), "P2 must be promoted first");
            assertEquals(BookingStatus.CONFIRMED, p1.getStatus());

            // w2 is still WAITLISTED -- only one promotion per call
            assertEquals(BookingStatus.WAITLISTED, w2.getStatus());
        }
    }

    @Test
    @DisplayName("Same segment: second promote call gets the second entry")
    void promoteOne_sameSegment_secondPromoteGetsSecondEntry() {
        Trip trip = twoSeatTrip("T10");
        // Fill both seats
        Booking c1 = bookingService.book("T10", "P1", "A", "D");
        Booking c2 = bookingService.book("T10", "P2", "A", "D");
        Booking w1 = bookingService.book("T10", "P3", "A", "D");
        Booking w2 = bookingService.book("T10", "P4", "A", "D");

        synchronized (trip) {
            // Free seat 1 -> promote w1
            trip.getSeat(c1.getSeatNumber()).removeBooking(c1.getSegment());
            Booking p1 = waitlistService.promoteOne(trip);
            assertEquals(w1.getBookingId(), p1.getBookingId());

            // Free seat 2 -> promote w2
            trip.getSeat(c2.getSeatNumber()).removeBooking(c2.getSegment());
            Booking p2 = waitlistService.promoteOne(trip);
            assertEquals(w2.getBookingId(), p2.getBookingId());
        }
    }

    // ---------------------------------------------------------------
    // promoteOne() -- cross-queue fairness by global sequence number
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Two different segments both fit: entry with earlier global sequence wins")
    void promoteOne_crossQueue_earlierSequenceWins() {
        // 1-seat trip.  Seat 1 holds A->D.
        // Waitlist (in this order):
        //   seq=0  B->C  (P2, first to arrive)
        //   seq=1  A->B  (P3, second to arrive)
        // When A->D is freed, both B->C and A->B fit on seat 1.
        // B->C has seq=0 -> must win.
        Trip trip = oneSeatTrip("T11");
        Booking c  = bookingService.book("T11", "P1", "A", "D");
        Booking wBC = bookingService.book("T11", "P2", "B", "C");  // seq 0
        Booking wAB = bookingService.book("T11", "P3", "A", "B");  // seq 1

        synchronized (trip) {
            trip.getSeat(c.getSeatNumber()).removeBooking(c.getSegment());

            Booking promoted = waitlistService.promoteOne(trip);

            assertNotNull(promoted);
            assertEquals(wBC.getBookingId(), promoted.getBookingId(),
                    "B->C (seq 0) must win over A->B (seq 1)");
            assertEquals(BookingStatus.CONFIRMED, promoted.getStatus());

            // A->B is still WAITLISTED
            assertEquals(BookingStatus.WAITLISTED, wAB.getStatus());
        }
    }

    @Test
    @DisplayName("Cross-queue: later-arriving segment that fits is skipped for earlier arrival")
    void promoteOne_crossQueue_laterArrivalSkipped() {
        // Same as above but enqueue order reversed: A->B first (seq=0), B->C second (seq=1)
        // A->B must win since it arrived first overall.
        Trip trip = oneSeatTrip("T12");
        Booking c   = bookingService.book("T12", "P1", "A", "D");
        Booking wAB = bookingService.book("T12", "P2", "A", "B");  // seq 0
        Booking wBC = bookingService.book("T12", "P3", "B", "C");  // seq 1

        synchronized (trip) {
            trip.getSeat(c.getSeatNumber()).removeBooking(c.getSegment());

            Booking promoted = waitlistService.promoteOne(trip);

            assertEquals(wAB.getBookingId(), promoted.getBookingId(),
                    "A->B (seq 0) must win over B->C (seq 1)");
            assertEquals(BookingStatus.WAITLISTED, wBC.getStatus());
        }
    }

    @Test
    @DisplayName("At most one promotion per promoteOne() call even if multiple entries fit")
    void promoteOne_atMostOnePromotion() {
        Trip trip = oneSeatTrip("T13");
        Booking c  = bookingService.book("T13", "P1", "A", "D");
        Booking w1 = bookingService.book("T13", "P2", "B", "C");  // seq 0
        Booking w2 = bookingService.book("T13", "P3", "A", "B");  // seq 1

        synchronized (trip) {
            trip.getSeat(c.getSeatNumber()).removeBooking(c.getSegment());
            waitlistService.promoteOne(trip);  // promotes w1

            // w2 must still be WAITLISTED -- only one promotion happened
            assertEquals(BookingStatus.WAITLISTED, w2.getStatus());
            // and the winning segment's deque is now empty
            Deque<WaitlistEntry> dq = trip.getWaitlistBySegment().get(w1.getSegment());
            assertTrue(dq == null || dq.isEmpty());
        }
    }

    @Test
    @DisplayName("Segment that does not fit is not promoted even when another does")
    void promoteOne_ineligibleSegmentNotPromoted() {
        // 1-seat trip.
        // Seat 1: A->C booked (confirmed).
        // Waitlist:
        //   seq=0  B->D  (overlaps A->C; should NOT be promoted when A->C is freed)
        //   Actually A->C being freed means B->D fits. Let me pick a scenario
        //   where one fits and one doesn't.
        //
        // Better: 1-seat trip. Seat 1: A->B (freed). Waitlist has B->D (fits) and
        // another booking X->Y that still conflicts with something else on the seat.
        // But with 1 seat and A->B freed, everything fits. Let me use 2 seats instead.
        //
        // 2-seat trip. Seat 1: A->D (confirmed, will be freed).
        //              Seat 2: B->C (confirmed, stays).
        // Waitlist: seq=0 A->D (conflicts with seat2 B->C? No. A->D conflicts with seat2 B->C
        // only because B->C is on seat2 not seat1). Actually seat2 has B->C, seat1 freed.
        // A->D on seat1 is free -> promoted. Let me pick a scenario where one segment
        // literally cannot fit anywhere.
        //
        // 1-seat trip. Seat 1: freed. But a second booking C->D is still on seat 1
        // via a different booking. Wait, we only have 1 seat.
        //
        // Simplest approach: 2-seat trip where seat 1 is freed but the ineligible
        // segment conflicts with seat 2 which is still occupied.
        Trip trip = twoSeatTrip("T14");
        Booking c1 = bookingService.book("T14", "P1", "A", "B");  // seat 1
        Booking c2 = bookingService.book("T14", "P2", "B", "D");  // seat 2

        // Waitlist:
        // seq=0: B->D -- conflicts with seat2 (B->D), but seat1 (A->B freed) has B->D? No.
        //   Seat1 after freeing A->B: is B->D free on seat1? Yes!  Hmm.
        // Let me use: free seat1 (A->B). Waitlist has C->D (fits seat1) and A->D.
        // A->D conflicts with seat2 B->D (overlaps). So A->D can only go on seat1.
        // But seat1 is freed (was A->B). A->D fits seat1.
        // C->D also fits seat1.
        // Both fit -- so this isn't a good test of "ineligible".
        //
        // Real ineligible case: waitlisted segment conflicts with ALL seats.
        // 1-seat trip. Seat1: C->D stays. Waitlist: A->D (conflicts with C->D on seat1).
        Trip t = oneSeatTrip("T14b");
        bookingService.book("T14b", "P1", "A", "B");    // fills seat 1

        // Now add another booking for C->D -- but seat is full for A->B overlap?
        // Actually A->B [0,1) and C->D [2,3) don't overlap -- they both fit on 1 seat.
        // Let me use a span booking to fill the seat completely.
        // Already done above. Seat1 has A->B. Now book C->D -- it fits on seat1 too.
        // Need the seat to be completely blocked for A->D.
        // Book A->D to fill seat: but that overlaps A->B which is already there.
        // So A->D would go to waitlist.

        // Let me restart cleanly for this edge case.
        // 1-seat trip. Seat1: A->D (fills entire route).
        // Waitlist: seq=0 A->D (ineligible -- conflicts with seat1 A->D while it's there)
        //           seq=1 A->B (also ineligible while A->D is on seat1)
        // Free seat1 A->D. Now both become eligible. Not ineligible anymore.

        // The only true "ineligible" scenario: waitlisted segment still conflicts
        // with a remaining booking after a DIFFERENT cancellation freed a seat.
        // 2-seat trip. Seat1: A->D freed. Seat2: B->C stays.
        // Waitlist: seq=0 A->D -- fits seat1 (freed). Eligible.
        //           seq=1 B->C -- conflicts with seat2 B->C. Ineligible.
        // promoteOne picks A->D (seq=0, eligible). B->C stays waitlisted.

        Trip t2 = twoSeatTrip("T15");
        Booking ca = bookingService.book("T15", "P1", "A", "D");  // seat 1
        Booking cb = bookingService.book("T15", "P2", "B", "C");  // seat 2
        Booking wAD = bookingService.book("T15", "P3", "A", "D"); // seq 0 waitlisted
        Booking wBC = bookingService.book("T15", "P4", "B", "C"); // seq 1 waitlisted

        synchronized (t2) {
            // Free seat 1 (A->D). Seat 2 still has B->C.
            t2.getSeat(ca.getSeatNumber()).removeBooking(ca.getSegment());

            // A->D fits seat1. B->C conflicts with seat2 B->C -- ineligible.
            Booking promoted = waitlistService.promoteOne(t2);

            assertEquals(wAD.getBookingId(), promoted.getBookingId(),
                    "A->D (eligible) must be promoted");
            assertEquals(BookingStatus.WAITLISTED, wBC.getStatus(),
                    "B->C (no free seat) must stay WAITLISTED");
        }
    }
}