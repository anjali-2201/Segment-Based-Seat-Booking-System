package com.shuttle.service;

import com.shuttle.domain.*;
import com.shuttle.exception.InvalidSegmentException;
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
 * Phase 3 tests for BookingService.book() happy path.
 *
 * Route used in all tests:
 *   A (idx 0, 10:00) --> B (idx 1, 10:15) --> C (idx 2, 10:30) --> D (idx 3, 10:45)
 *
 * Trip has 2 seats unless overridden.
 */
@DisplayName("BookingService -- book() happy path")
class BookingServiceTest {

    // ---- shared fixtures ------------------------------------------------
    private Stop stopA, stopB, stopC, stopD;
    private Route route;
    private TripRepository repo;
    private BookingService service;
    private Trip trip;          // 2-seat trip by default

    @BeforeEach
    void setUp() {
        stopA = new Stop("A", "Alpha",   0, LocalTime.of(10, 0));
        stopB = new Stop("B", "Bravo",   1, LocalTime.of(10, 15));
        stopC = new Stop("C", "Charlie", 2, LocalTime.of(10, 30));
        stopD = new Stop("D", "Delta",   3, LocalTime.of(10, 45));

        route   = new Route("R1", "City Loop", List.of(stopA, stopB, stopC, stopD));
        trip    = new Trip("T1", route, LocalDate.of(2026, 9, 23), 2);

        repo    = new TripRepository();
        repo.save(trip);
        service = new BookingService(repo);
    }

    // ---------------------------------------------------------------
    // Happy path: confirmed booking
    // ---------------------------------------------------------------

    @Test
    @DisplayName("book() returns CONFIRMED booking with assigned seat")
    void book_confirmedBooking() {
        Booking b = service.book("T1", "P1", "A", "C");

        assertEquals(BookingStatus.CONFIRMED, b.getStatus());
        assertNotNull(b.getSeatNumber(), "Seat must be assigned for CONFIRMED booking");
        assertEquals("T1", b.getTripId());
        assertEquals("P1", b.getPassengerId());
        assertEquals(new Segment(0, 2), b.getSegment());
    }

    @Test
    @DisplayName("Booking is registered in trip.bookings map")
    void book_registeredInTripBookings() {
        Booking b = service.book("T1", "P1", "A", "D");

        Booking found = service.getBooking("T1", b.getBookingId());
        assertEquals(b.getBookingId(), found.getBookingId());
    }

    @Test
    @DisplayName("Seat's TreeMap contains the booking after book()")
    void book_seatTreeMapUpdated() {
        Booking b = service.book("T1", "P1", "A", "C");

        Seat seat = trip.getSeat(b.getSeatNumber());
        assertFalse(seat.isFree(new Segment(0, 2)),
                "Seat must no longer be free for the same segment");
    }

    // ---------------------------------------------------------------
    // Crown jewel: seat reuse on non-overlapping segments
    // ---------------------------------------------------------------

    @Test
    @DisplayName("A->B then B->D on same seat (touching = no overlap)")
    void book_seatReuse_touchingSegments() {
        // With 1-seat trip, both must land on seat 1
        Trip singleSeatTrip = new Trip("T2", route, LocalDate.of(2026, 9, 23), 1);
        repo.save(singleSeatTrip);

        Booking ab = service.book("T2", "P1", "A", "B");
        Booking bd = service.book("T2", "P2", "B", "D");

        assertEquals(BookingStatus.CONFIRMED, ab.getStatus(), "A->B must be CONFIRMED");
        assertEquals(BookingStatus.CONFIRMED, bd.getStatus(), "B->D must be CONFIRMED");
        assertEquals(1, (int) ab.getSeatNumber(), "Both must share seat 1");
        assertEquals(1, (int) bd.getSeatNumber(), "Both must share seat 1");
    }

    @Test
    @DisplayName("A->B, B->C, C->D all fit on a single seat")
    void book_seatReuse_threePassengers_oneSeat() {
        Trip singleSeatTrip = new Trip("T3", route, LocalDate.of(2026, 9, 23), 1);
        repo.save(singleSeatTrip);

        Booking ab = service.book("T3", "P1", "A", "B");
        Booking bc = service.book("T3", "P2", "B", "C");
        Booking cd = service.book("T3", "P3", "C", "D");

        assertEquals(BookingStatus.CONFIRMED, ab.getStatus());
        assertEquals(BookingStatus.CONFIRMED, bc.getStatus());
        assertEquals(BookingStatus.CONFIRMED, cd.getStatus());
        // All three on the single seat
        assertEquals(1, (int) ab.getSeatNumber());
        assertEquals(1, (int) bc.getSeatNumber());
        assertEquals(1, (int) cd.getSeatNumber());
    }

    // ---------------------------------------------------------------
    // Overlapping segment -> WAITLISTED (not an exception)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Overlapping booking on full trip goes to WAITLISTED, not exception")
    void book_noSeatFree_returnsWaitlisted() {
        // Fill both seats with A->D
        service.book("T1", "P1", "A", "D");
        service.book("T1", "P2", "A", "D");

        // Third request for overlapping segment must be WAITLISTED
        Booking waitlisted = service.book("T1", "P3", "B", "C");

        assertEquals(BookingStatus.WAITLISTED, waitlisted.getStatus());
        assertNull(waitlisted.getSeatNumber(), "WAITLISTED booking must have no seat");
    }

    @Test
    @DisplayName("WAITLISTED booking is stored in trip.bookings")
    void book_waitlisted_registeredInTripBookings() {
        service.book("T1", "P1", "A", "D");
        service.book("T1", "P2", "A", "D");
        Booking waitlisted = service.book("T1", "P3", "A", "D");

        Booking found = service.getBooking("T1", waitlisted.getBookingId());
        assertEquals(BookingStatus.WAITLISTED, found.getStatus());
    }

    @Test
    @DisplayName("Non-overlapping segment on full trip still gets CONFIRMED on a free slot")
    void book_nonOverlapping_stillConfirmedWhenPartiallyFull() {
        // Seat 1: A->B, Seat 2: A->D
        service.book("T1", "P1", "A", "B");
        service.book("T1", "P2", "A", "D");

        // B->D overlaps seat 2 (A->D), but seat 1 is free for B->D
        Booking bd = service.book("T1", "P3", "B", "D");
        assertEquals(BookingStatus.CONFIRMED, bd.getStatus());
        assertEquals(1, (int) bd.getSeatNumber(), "Should land on seat 1 (first free)");
    }

    // ---------------------------------------------------------------
    // Multiple bookings -- each gets a unique bookingId
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Each booking gets a unique bookingId")
    void book_uniqueBookingIds() {
        Booking b1 = service.book("T1", "P1", "A", "B");
        Booking b2 = service.book("T1", "P2", "C", "D");

        assertNotEquals(b1.getBookingId(), b2.getBookingId());
    }

    // ---------------------------------------------------------------
    // Validation: TripNotFoundException
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Unknown tripId throws TripNotFoundException")
    void book_unknownTrip_throws() {
        assertThrows(TripNotFoundException.class,
                () -> service.book("UNKNOWN", "P1", "A", "C"));
    }

    // ---------------------------------------------------------------
    // Validation: InvalidSegmentException
    // ---------------------------------------------------------------

    @Test
    @DisplayName("fromStop not on route throws InvalidSegmentException")
    void book_fromStopNotOnRoute_throws() {
        assertThrows(InvalidSegmentException.class,
                () -> service.book("T1", "P1", "Z", "C"));
    }

    @Test
    @DisplayName("toStop not on route throws InvalidSegmentException")
    void book_toStopNotOnRoute_throws() {
        assertThrows(InvalidSegmentException.class,
                () -> service.book("T1", "P1", "A", "Z"));
    }

    @Test
    @DisplayName("fromStop == toStop throws InvalidSegmentException")
    void book_sameStop_throws() {
        assertThrows(InvalidSegmentException.class,
                () -> service.book("T1", "P1", "B", "B"));
    }

    @Test
    @DisplayName("fromStop after toStop in route order throws InvalidSegmentException")
    void book_reversedStops_throws() {
        assertThrows(InvalidSegmentException.class,
                () -> service.book("T1", "P1", "D", "A"));
    }
}