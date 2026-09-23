package com.shuttle.util;

import com.shuttle.domain.*;
import com.shuttle.exception.BookingNotFoundException;
import com.shuttle.exception.InvalidBookingStateException;
import com.shuttle.exception.InvalidSegmentException;
import com.shuttle.exception.ShuttleBookingException;
import com.shuttle.exception.TripNotFoundException;
import com.shuttle.repository.TripRepository;
import com.shuttle.service.BookingService;
import com.shuttle.service.WaitlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive validation tests for all service entry points.
 *
 * Organised as nested test classes, one per entry point, so failures are
 * immediately traceable to a specific operation.
 *
 * All invalid inputs must throw a ShuttleBookingException subclass --
 * never a raw NullPointerException or IllegalStateException.
 *
 * Route:  A(0) -> B(1) -> C(2) -> D(3)
 */
@DisplayName("Input validation -- all entry points")
class ValidationTest {

    private TripRepository repo;
    private BookingService service;
    private Route route;
    private Stop stopA, stopB, stopC, stopD;

    @BeforeEach
    void setUp() {
        stopA = new Stop("A", "Alpha",   0, LocalTime.of(10, 0));
        stopB = new Stop("B", "Bravo",   1, LocalTime.of(10, 15));
        stopC = new Stop("C", "Charlie", 2, LocalTime.of(10, 30));
        stopD = new Stop("D", "Delta",   3, LocalTime.of(10, 45));

        route   = new Route("R1", "Loop", List.of(stopA, stopB, stopC, stopD));
        repo    = new TripRepository();
        service = new BookingService(repo, new WaitlistService());

        // A default 2-seat trip available for most tests
        repo.save(new Trip("T1", route, LocalDate.of(2026, 9, 23), 2));
    }

    // ===================================================================
    // book() validation
    // ===================================================================

    @Nested
    @DisplayName("book() -- null / blank inputs")
    class BookNullBlankInputs {

        @Test
        @DisplayName("null tripId throws InvalidSegmentException")
        void nullTripId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book(null, "P1", "A", "D"));
        }

        @Test
        @DisplayName("blank tripId throws InvalidSegmentException")
        void blankTripId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("  ", "P1", "A", "D"));
        }

        @Test
        @DisplayName("null passengerId throws InvalidSegmentException")
        void nullPassengerId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", null, "A", "D"));
        }

        @Test
        @DisplayName("blank passengerId throws InvalidSegmentException")
        void blankPassengerId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "", "A", "D"));
        }

        @Test
        @DisplayName("null fromStopId throws InvalidSegmentException")
        void nullFromStopId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "P1", null, "D"));
        }

        @Test
        @DisplayName("blank fromStopId throws InvalidSegmentException")
        void blankFromStopId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "P1", "", "D"));
        }

        @Test
        @DisplayName("null toStopId throws InvalidSegmentException")
        void nullToStopId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "P1", "A", null));
        }

        @Test
        @DisplayName("blank toStopId throws InvalidSegmentException")
        void blankToStopId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "P1", "A", "  "));
        }
    }

    @Nested
    @DisplayName("book() -- trip and stop validation")
    class BookTripStopValidation {

        @Test
        @DisplayName("Unknown tripId throws TripNotFoundException")
        void unknownTripId() {
            assertThrows(TripNotFoundException.class,
                    () -> service.book("UNKNOWN", "P1", "A", "D"));
        }

        @Test
        @DisplayName("fromStop not on route throws InvalidSegmentException")
        void fromStopNotOnRoute() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "P1", "Z", "D"));
        }

        @Test
        @DisplayName("toStop not on route throws InvalidSegmentException")
        void toStopNotOnRoute() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "P1", "A", "Z"));
        }

        @Test
        @DisplayName("Both stops not on route throws InvalidSegmentException")
        void bothStopsNotOnRoute() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "P1", "X", "Y"));
        }
    }

    @Nested
    @DisplayName("book() -- segment direction validation")
    class BookSegmentDirection {

        @Test
        @DisplayName("fromStop == toStop throws InvalidSegmentException")
        void sameStop() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "P1", "B", "B"));
        }

        @Test
        @DisplayName("fromStop after toStop throws InvalidSegmentException (reversed)")
        void reversedDirection() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "P1", "D", "A"));
        }

        @Test
        @DisplayName("Adjacent reversed pair throws InvalidSegmentException")
        void adjacentReversed() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.book("T1", "P1", "C", "B"));
        }

        @Test
        @DisplayName("Valid forward direction does NOT throw")
        void validForwardDirection() {
            assertDoesNotThrow(() -> service.book("T1", "P1", "A", "D"));
        }
    }

    @Nested
    @DisplayName("book() -- exceptions are ShuttleBookingException subclasses")
    class BookExceptionHierarchy {

        @Test
        @DisplayName("TripNotFoundException is a ShuttleBookingException")
        void tripNotFoundIsShuttleException() {
            assertThrows(ShuttleBookingException.class,
                    () -> service.book("UNKNOWN", "P1", "A", "D"));
        }

        @Test
        @DisplayName("InvalidSegmentException is a ShuttleBookingException")
        void invalidSegmentIsShuttleException() {
            assertThrows(ShuttleBookingException.class,
                    () -> service.book("T1", "P1", "D", "A"));
        }
    }

    // ===================================================================
    // cancel() validation
    // ===================================================================

    @Nested
    @DisplayName("cancel() -- null / blank inputs")
    class CancelNullBlankInputs {

        @Test
        @DisplayName("null tripId throws InvalidSegmentException")
        void nullTripId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.cancel(null, "someId"));
        }

        @Test
        @DisplayName("blank tripId throws InvalidSegmentException")
        void blankTripId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.cancel("", "someId"));
        }

        @Test
        @DisplayName("null bookingId throws InvalidSegmentException")
        void nullBookingId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.cancel("T1", null));
        }

        @Test
        @DisplayName("blank bookingId throws InvalidSegmentException")
        void blankBookingId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.cancel("T1", "   "));
        }
    }

    @Nested
    @DisplayName("cancel() -- trip and booking validation")
    class CancelTripBookingValidation {

        @Test
        @DisplayName("Unknown tripId throws TripNotFoundException")
        void unknownTrip() {
            assertThrows(TripNotFoundException.class,
                    () -> service.cancel("UNKNOWN", "bid"));
        }

        @Test
        @DisplayName("Unknown bookingId throws BookingNotFoundException")
        void unknownBooking() {
            assertThrows(BookingNotFoundException.class,
                    () -> service.cancel("T1", "no-such-booking"));
        }

        @Test
        @DisplayName("Cancelling a WAITLISTED booking throws InvalidBookingStateException")
        void cancelWaitlisted() {
            // Fill both seats to force waitlist
            service.book("T1", "P1", "A", "D");
            service.book("T1", "P2", "A", "D");
            Booking w = service.book("T1", "P3", "A", "D");
            assertEquals(BookingStatus.WAITLISTED, w.getStatus());

            assertThrows(InvalidBookingStateException.class,
                    () -> service.cancel("T1", w.getBookingId()));
        }

        @Test
        @DisplayName("Double-cancel throws InvalidBookingStateException on second call")
        void doubleCancel() {
            Booking b = service.book("T1", "P1", "A", "D");
            service.cancel("T1", b.getBookingId());

            assertThrows(InvalidBookingStateException.class,
                    () -> service.cancel("T1", b.getBookingId()));
        }

        @Test
        @DisplayName("Cancelling a NO_SHOW booking throws InvalidBookingStateException")
        void cancelNoShow() {
            Booking b = service.book("T1", "P1", "A", "D");
            service.markNoShow("T1", b.getBookingId());

            assertThrows(InvalidBookingStateException.class,
                    () -> service.cancel("T1", b.getBookingId()));
        }
    }

    // ===================================================================
    // markNoShow() validation
    // ===================================================================

    @Nested
    @DisplayName("markNoShow() -- null / blank inputs")
    class MarkNoShowNullBlankInputs {

        @Test
        @DisplayName("null tripId throws InvalidSegmentException")
        void nullTripId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.markNoShow(null, "bid"));
        }

        @Test
        @DisplayName("null bookingId throws InvalidSegmentException")
        void nullBookingId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.markNoShow("T1", null));
        }
    }

    @Nested
    @DisplayName("markNoShow() -- booking state validation")
    class MarkNoShowStateValidation {

        @Test
        @DisplayName("Unknown tripId throws TripNotFoundException")
        void unknownTrip() {
            assertThrows(TripNotFoundException.class,
                    () -> service.markNoShow("UNKNOWN", "bid"));
        }

        @Test
        @DisplayName("Unknown bookingId throws BookingNotFoundException")
        void unknownBooking() {
            assertThrows(BookingNotFoundException.class,
                    () -> service.markNoShow("T1", "no-such-booking"));
        }

        @Test
        @DisplayName("markNoShow on WAITLISTED throws InvalidBookingStateException")
        void markNoShowWaitlisted() {
            service.book("T1", "P1", "A", "D");
            service.book("T1", "P2", "A", "D");
            Booking w = service.book("T1", "P3", "A", "D");

            assertThrows(InvalidBookingStateException.class,
                    () -> service.markNoShow("T1", w.getBookingId()));
        }

        @Test
        @DisplayName("markNoShow on CANCELLED throws InvalidBookingStateException")
        void markNoShowCancelled() {
            Booking b = service.book("T1", "P1", "A", "D");
            service.cancel("T1", b.getBookingId());

            assertThrows(InvalidBookingStateException.class,
                    () -> service.markNoShow("T1", b.getBookingId()));
        }

        @Test
        @DisplayName("Double markNoShow throws InvalidBookingStateException")
        void doubleMarkNoShow() {
            Booking b = service.book("T1", "P1", "A", "D");
            service.markNoShow("T1", b.getBookingId());

            assertThrows(InvalidBookingStateException.class,
                    () -> service.markNoShow("T1", b.getBookingId()));
        }
    }

    // ===================================================================
    // getBooking() validation
    // ===================================================================

    @Nested
    @DisplayName("getBooking() -- validation")
    class GetBookingValidation {

        @Test
        @DisplayName("null tripId throws InvalidSegmentException")
        void nullTripId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.getBooking(null, "bid"));
        }

        @Test
        @DisplayName("null bookingId throws InvalidSegmentException")
        void nullBookingId() {
            assertThrows(InvalidSegmentException.class,
                    () -> service.getBooking("T1", null));
        }

        @Test
        @DisplayName("Unknown tripId throws TripNotFoundException")
        void unknownTrip() {
            assertThrows(TripNotFoundException.class,
                    () -> service.getBooking("UNKNOWN", "bid"));
        }

        @Test
        @DisplayName("Unknown bookingId throws BookingNotFoundException")
        void unknownBooking() {
            assertThrows(BookingNotFoundException.class,
                    () -> service.getBooking("T1", "no-such-booking"));
        }

        @Test
        @DisplayName("Valid tripId and bookingId returns the booking")
        void validLookup() {
            Booking b = service.book("T1", "P1", "A", "D");
            Booking found = service.getBooking("T1", b.getBookingId());
            assertEquals(b.getBookingId(), found.getBookingId());
        }
    }

    // ===================================================================
    // Domain object construction guards
    // ===================================================================

    @Nested
    @DisplayName("Domain object construction guards")
    class DomainConstructionGuards {

        @Test
        @DisplayName("Stop rejects negative sequenceIndex")
        void stopNegativeIndex() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Stop("X", "X", -1, LocalTime.NOON));
        }

        @Test
        @DisplayName("Seat rejects seatNumber < 1")
        void seatInvalidNumber() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Seat(0));
        }

        @Test
        @DisplayName("Trip rejects totalSeats < 1")
        void tripZeroSeats() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Trip("X", route, LocalDate.now(), 0));
        }

        @Test
        @DisplayName("Route rejects empty stop list")
        void routeEmptyStops() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Route("X", "X", List.of()));
        }

        @Test
        @DisplayName("Segment rejects fromIdx == toIdx")
        void segmentSameIndex() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Segment(2, 2));
        }

        @Test
        @DisplayName("Segment rejects fromIdx > toIdx")
        void segmentReversedIndex() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Segment(3, 1));
        }
    }
}