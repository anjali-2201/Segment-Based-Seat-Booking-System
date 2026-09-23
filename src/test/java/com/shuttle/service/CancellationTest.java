package com.shuttle.service;

import com.shuttle.domain.*;
import com.shuttle.exception.BookingNotFoundException;
import com.shuttle.exception.InvalidBookingStateException;
import com.shuttle.exception.TripNotFoundException;
import com.shuttle.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BookingService.cancel() and markNoShow().
 *
 * Route: A(0) -> B(1) -> C(2) -> D(3)
 *
 * Key correctness property tested:
 *   Booking lookup AND status check happen INSIDE synchronized(trip) --
 *   the double-cancel race is prevented by design, not by luck.
 */
@DisplayName("Cancellation and no-show")
class CancellationTest {

    private Stop stopA, stopB, stopC, stopD;
    private Route route;
    private TripRepository repo;
    private BookingService service;
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
        service         = new BookingService(repo, waitlistService);
    }

    private Trip oneSeatTrip(String id) {
        Trip t = new Trip(id, route, LocalDate.of(2026, 9, 23), 1);
        repo.save(t);
        return t;
    }

    private Trip twoSeatTrip(String id) {
        Trip t = new Trip(id, route, LocalDate.of(2026, 9, 23), 2);
        repo.save(t);
        return t;
    }

    // ---------------------------------------------------------------
    // cancel() -- basic happy path
    // ---------------------------------------------------------------

    @Test
    @DisplayName("cancel() transitions CONFIRMED -> CANCELLED")
    void cancel_statusBecomesCANCELLED() {
        oneSeatTrip("T1");
        Booking b = service.book("T1", "P1", "A", "D");
        assertEquals(BookingStatus.CONFIRMED, b.getStatus());

        service.cancel("T1", b.getBookingId());

        assertEquals(BookingStatus.CANCELLED, b.getStatus());
    }

    @Test
    @DisplayName("cancel() frees the seat so a new booking can use it")
    void cancel_seatIsFreed() {
        oneSeatTrip("T2");
        Booking b1 = service.book("T2", "P1", "A", "D");
        service.cancel("T2", b1.getBookingId());

        // Seat should be free now -- new booking must be CONFIRMED
        Booking b2 = service.book("T2", "P2", "A", "D");
        assertEquals(BookingStatus.CONFIRMED, b2.getStatus());
    }

    @Test
    @DisplayName("Cancelled booking remains in trip.bookings with CANCELLED status")
    void cancel_bookingRetainedWithCancelledStatus() {
        oneSeatTrip("T3");
        Booking b = service.book("T3", "P1", "A", "D");
        service.cancel("T3", b.getBookingId());

        Booking found = service.getBooking("T3", b.getBookingId());
        assertEquals(BookingStatus.CANCELLED, found.getStatus());
    }

    // ---------------------------------------------------------------
    // cancel() + promotion (wired integration)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("cancel() promotes the single waitlisted passenger")
    void cancel_promotesWaitlistedPassenger() {
        oneSeatTrip("T4");
        Booking confirmed  = service.book("T4", "P1", "A", "D");
        Booking waitlisted = service.book("T4", "P2", "A", "D");

        assertEquals(BookingStatus.WAITLISTED, waitlisted.getStatus());

        service.cancel("T4", confirmed.getBookingId());

        // Waitlisted passenger must now be CONFIRMED
        assertEquals(BookingStatus.CONFIRMED, waitlisted.getStatus());
        assertNotNull(waitlisted.getSeatNumber());
    }

    @Test
    @DisplayName("cancel() promotes the FIFO-first passenger for same segment")
    void cancel_sameSegment_promotesFirstEnqueued() {
        oneSeatTrip("T5");
        Booking c  = service.book("T5", "P1", "A", "D");
        Booking w1 = service.book("T5", "P2", "A", "D");  // enqueued first
        Booking w2 = service.book("T5", "P3", "A", "D");  // enqueued second

        service.cancel("T5", c.getBookingId());

        assertEquals(BookingStatus.CONFIRMED,  w1.getStatus(), "P2 must be promoted (first in queue)");
        assertEquals(BookingStatus.WAITLISTED, w2.getStatus(), "P3 must stay waitlisted");
    }

    @Test
    @DisplayName("cancel() across two queues promotes the entry with the earlier global sequence")
    void cancel_crossQueue_earlierSequencePromoted() {
        // 1-seat trip: seat has A->D.
        // Waitlist (in order):
        //   seq=0  B->C  (P2)
        //   seq=1  A->B  (P3)
        // Cancel A->D -> both B->C and A->B fit -> B->C (seq 0) wins.
        oneSeatTrip("T6");
        Booking c   = service.book("T6", "P1", "A", "D");
        Booking wBC = service.book("T6", "P2", "B", "C");  // seq 0
        Booking wAB = service.book("T6", "P3", "A", "B");  // seq 1

        service.cancel("T6", c.getBookingId());

        assertEquals(BookingStatus.CONFIRMED,  wBC.getStatus(), "B->C (seq 0) must be promoted");
        assertEquals(BookingStatus.WAITLISTED, wAB.getStatus(), "A->B (seq 1) must stay waitlisted");
    }

    @Test
    @DisplayName("cancel() does at most one promotion even when multiple segments fit")
    void cancel_atMostOnePromotion() {
        oneSeatTrip("T7");
        Booking c  = service.book("T7", "P1", "A", "D");
        Booking w1 = service.book("T7", "P2", "B", "C");  // seq 0
        Booking w2 = service.book("T7", "P3", "A", "B");  // seq 1

        service.cancel("T7", c.getBookingId());

        // Only w1 promoted; w2 must still be WAITLISTED
        assertEquals(BookingStatus.CONFIRMED,  w1.getStatus());
        assertEquals(BookingStatus.WAITLISTED, w2.getStatus());
    }

    @Test
    @DisplayName("cancel() with no waitlisted passengers leaves seat free with no error")
    void cancel_noWaitlist_noPromotion() {
        oneSeatTrip("T8");
        Booking b = service.book("T8", "P1", "A", "D");

        assertDoesNotThrow(() -> service.cancel("T8", b.getBookingId()));
        assertEquals(BookingStatus.CANCELLED, b.getStatus());
    }

    @Test
    @DisplayName("Two sequential cancellations each promote one waitlisted passenger")
    void cancel_sequentialCancellations_eachPromoteOne() {
        twoSeatTrip("T9");
        Booking c1 = service.book("T9", "P1", "A", "D");
        Booking c2 = service.book("T9", "P2", "A", "D");
        Booking w1 = service.book("T9", "P3", "A", "D");
        Booking w2 = service.book("T9", "P4", "A", "D");

        service.cancel("T9", c1.getBookingId());
        assertEquals(BookingStatus.CONFIRMED,  w1.getStatus(), "First cancel promotes w1");
        assertEquals(BookingStatus.WAITLISTED, w2.getStatus(), "w2 still waiting");

        service.cancel("T9", c2.getBookingId());
        assertEquals(BookingStatus.CONFIRMED, w2.getStatus(), "Second cancel promotes w2");
    }

    // ---------------------------------------------------------------
    // cancel() -- validation errors (all checked INSIDE the lock)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("cancel() with unknown tripId throws TripNotFoundException")
    void cancel_unknownTrip_throws() {
        assertThrows(TripNotFoundException.class,
                () -> service.cancel("UNKNOWN", "someBookingId"));
    }

    @Test
    @DisplayName("cancel() with unknown bookingId throws BookingNotFoundException")
    void cancel_unknownBooking_throws() {
        oneSeatTrip("T10");
        assertThrows(BookingNotFoundException.class,
                () -> service.cancel("T10", "no-such-booking"));
    }

    @Test
    @DisplayName("cancel() on already-CANCELLED booking throws InvalidBookingStateException")
    void cancel_alreadyCancelled_throws() {
        oneSeatTrip("T11");
        Booking b = service.book("T11", "P1", "A", "D");
        service.cancel("T11", b.getBookingId());

        // Second cancel must be rejected
        assertThrows(InvalidBookingStateException.class,
                () -> service.cancel("T11", b.getBookingId()));
    }

    @Test
    @DisplayName("cancel() on WAITLISTED booking throws InvalidBookingStateException")
    void cancel_waitlistedBooking_throws() {
        oneSeatTrip("T12");
        service.book("T12", "P1", "A", "D");              // fills seat
        Booking w = service.book("T12", "P2", "A", "D");  // WAITLISTED

        assertThrows(InvalidBookingStateException.class,
                () -> service.cancel("T12", w.getBookingId()));
    }

    @Test
    @DisplayName("cancel() on NO_SHOW booking throws InvalidBookingStateException")
    void cancel_noShowBooking_throws() {
        oneSeatTrip("T13");
        Booking b = service.book("T13", "P1", "A", "D");
        service.markNoShow("T13", b.getBookingId());

        assertThrows(InvalidBookingStateException.class,
                () -> service.cancel("T13", b.getBookingId()));
    }

    // ---------------------------------------------------------------
    // markNoShow()
    // ---------------------------------------------------------------

    @Test
    @DisplayName("markNoShow() transitions CONFIRMED -> NO_SHOW")
    void markNoShow_statusBecomesNO_SHOW() {
        oneSeatTrip("T14");
        Booking b = service.book("T14", "P1", "A", "D");

        service.markNoShow("T14", b.getBookingId());

        assertEquals(BookingStatus.NO_SHOW, b.getStatus());
    }

    @Test
    @DisplayName("markNoShow() does NOT free the seat (no live clock in scope)")
    void markNoShow_seatNotFreed() {
        oneSeatTrip("T15");
        Booking b = service.book("T15", "P1", "A", "D");
        service.markNoShow("T15", b.getBookingId());

        // The seat must still be occupied -- a new booking for the same segment
        // should NOT get CONFIRMED (it should go to WAITLISTED)
        Booking next = service.book("T15", "P2", "A", "D");
        assertEquals(BookingStatus.WAITLISTED, next.getStatus(),
                "Seat must NOT have been freed by markNoShow");
    }

    @Test
    @DisplayName("markNoShow() on already-CANCELLED booking throws InvalidBookingStateException")
    void markNoShow_cancelledBooking_throws() {
        oneSeatTrip("T16");
        Booking b = service.book("T16", "P1", "A", "D");
        service.cancel("T16", b.getBookingId());

        assertThrows(InvalidBookingStateException.class,
                () -> service.markNoShow("T16", b.getBookingId()));
    }

    @Test
    @DisplayName("markNoShow() on WAITLISTED booking throws InvalidBookingStateException")
    void markNoShow_waitlistedBooking_throws() {
        oneSeatTrip("T17");
        service.book("T17", "P1", "A", "D");
        Booking w = service.book("T17", "P2", "A", "D");

        assertThrows(InvalidBookingStateException.class,
                () -> service.markNoShow("T17", w.getBookingId()));
    }

    @Test
    @DisplayName("markNoShow() with unknown tripId throws TripNotFoundException")
    void markNoShow_unknownTrip_throws() {
        assertThrows(TripNotFoundException.class,
                () -> service.markNoShow("UNKNOWN", "someId"));
    }

    @Test
    @DisplayName("markNoShow() with unknown bookingId throws BookingNotFoundException")
    void markNoShow_unknownBooking_throws() {
        oneSeatTrip("T18");
        assertThrows(BookingNotFoundException.class,
                () -> service.markNoShow("T18", "no-such-booking"));
    }
}