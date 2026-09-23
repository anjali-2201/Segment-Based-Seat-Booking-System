package com.shuttle.service;

import com.shuttle.domain.Booking;
import com.shuttle.domain.BookingStatus;
import com.shuttle.domain.Seat;
import com.shuttle.domain.Segment;
import com.shuttle.domain.Trip;
import com.shuttle.exception.BookingNotFoundException;
import com.shuttle.exception.InvalidBookingStateException;
import com.shuttle.exception.TripNotFoundException;
import com.shuttle.repository.TripRepository;
import com.shuttle.util.Validator;

import java.util.UUID;

/**
 * Orchestrates booking, cancellation, and no-show marking.
 *
 * Threading model (blueprint section 8):
 *   - Trip lookup via ConcurrentHashMap is thread-safe without extra locking.
 *   - Input and segment validation read only immutable data -- no lock needed.
 *   - ALL mutations to trip booking state happen inside synchronized(trip).
 *   - Locking on the Trip instance means unrelated trips never contend.
 *
 * Validation order in book():
 *   1. Null/blank input check (Validator.validateBookingInputs) -- before ANY map lookup,
 *      so callers never see a raw NullPointerException from ConcurrentHashMap.get(null).
 *   2. Trip existence check (TripNotFoundException if not found).
 *   3. Stop membership and direction check (InvalidSegmentException).
 *   4. synchronized(trip) { check seats, assign or waitlist }.
 *
 * Validation order in cancel() / markNoShow():
 *   Booking lookup AND status check are INSIDE synchronized(trip) -- closes the
 *   double-cancel race (see Javadoc on cancel()).
 */
public class BookingService {

    private final TripRepository tripRepository;
    private final WaitlistService waitlistService;

    public BookingService(TripRepository tripRepository, WaitlistService waitlistService) {
        this.tripRepository  = tripRepository;
        this.waitlistService = waitlistService;
    }

    /** Convenience constructor -- creates a default WaitlistService. */
    public BookingService(TripRepository tripRepository) {
        this(tripRepository, new WaitlistService());
    }

    // ------------------------------------------------------------------
    // book()
    // ------------------------------------------------------------------

    /**
     * Books a segment on a trip for a passenger.
     *
     * Returns CONFIRMED if a seat is free, WAITLISTED otherwise.
     * Never throws for lack of seats.
     *
     * @throws com.shuttle.exception.InvalidSegmentException  null/blank inputs, stop not on
     *         route, same stop, or reversed direction
     * @throws TripNotFoundException                          unknown tripId
     */
    public Booking book(String tripId, String passengerId,
                        String fromStopId, String toStopId) {

        // Step 1: null/blank guard -- before any map lookup
        Validator.validateBookingInputs(tripId, passengerId, fromStopId, toStopId);

        // Step 2: trip lookup (ConcurrentHashMap -- no lock)
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        // Step 3: validate stops and direction (reads immutable Route -- no lock)
        int[] indices = Validator.validateSegment(trip, fromStopId, toStopId);
        Segment segment = new Segment(indices[0], indices[1]);

        // Step 4: atomic check-then-act
        synchronized (trip) {

            for (int seatNum = 1; seatNum <= trip.getTotalSeats(); seatNum++) {
                Seat seat = trip.getSeat(seatNum);
                if (seat.isFree(segment)) {
                    String bookingId = UUID.randomUUID().toString();
                    Booking booking  = new Booking(
                            bookingId, tripId, passengerId, segment,
                            seatNum, BookingStatus.CONFIRMED);
                    seat.addBooking(booking);
                    trip.getBookings().put(bookingId, booking);
                    return booking;
                }
            }

            // No free seat -- WAITLISTED
            String bookingId = UUID.randomUUID().toString();
            Booking booking  = new Booking(
                    bookingId, tripId, passengerId, segment,
                    null, BookingStatus.WAITLISTED);
            trip.getBookings().put(bookingId, booking);
            waitlistService.enqueue(trip, booking);
            return booking;
        }
    }

    // ------------------------------------------------------------------
    // cancel()
    // ------------------------------------------------------------------

    /**
     * Cancels a CONFIRMED booking and attempts to promote one waitlisted passenger.
     *
     * Booking lookup AND status check are INSIDE synchronized(trip).
     * Reason: if two threads call cancel() on the same booking concurrently,
     * checking status outside the lock lets both pass the CONFIRMED check before
     * either writes CANCELLED -- a double-free of the seat.  Inside the lock,
     * the second thread sees CANCELLED and is rejected cleanly.
     *
     * Cancel + promote is one atomic unit so no thread can observe a freed seat
     * without a simultaneous promotion attempt (blueprint section 9).
     *
     * @throws TripNotFoundException        unknown tripId
     * @throws BookingNotFoundException     unknown bookingId on this trip
     * @throws InvalidBookingStateException booking not in CONFIRMED status
     */
    public Booking cancel(String tripId, String bookingId) {
        Validator.requireNonBlank(tripId,    "tripId");
        Validator.requireNonBlank(bookingId, "bookingId");

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        synchronized (trip) {
            Booking booking = trip.getBookings().get(bookingId);
            if (booking == null) {
                throw new BookingNotFoundException(bookingId);
            }
            if (booking.getStatus() != BookingStatus.CONFIRMED) {
                throw new InvalidBookingStateException(
                        "Cannot cancel booking '" + bookingId
                        + "' with status " + booking.getStatus()
                        + " (only CONFIRMED bookings can be cancelled)");
            }

            trip.getSeat(booking.getSeatNumber()).removeBooking(booking.getSegment());
            booking.cancel();
            return waitlistService.promoteOne(trip);   // at most one, inside the lock
        }
    }

    // ------------------------------------------------------------------
    // markNoShow()
    // ------------------------------------------------------------------

    /**
     * Marks a CONFIRMED booking as NO_SHOW.
     * The seat is NOT freed -- no live trip-clock in scope (blueprint section 11).
     *
     * @throws TripNotFoundException        unknown tripId
     * @throws BookingNotFoundException     unknown bookingId on this trip
     * @throws InvalidBookingStateException booking not in CONFIRMED status
     */
    public void markNoShow(String tripId, String bookingId) {
        Validator.requireNonBlank(tripId,    "tripId");
        Validator.requireNonBlank(bookingId, "bookingId");

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        synchronized (trip) {
            Booking booking = trip.getBookings().get(bookingId);
            if (booking == null) {
                throw new BookingNotFoundException(bookingId);
            }
            if (booking.getStatus() != BookingStatus.CONFIRMED) {
                throw new InvalidBookingStateException(
                        "Cannot mark no-show for booking '" + bookingId
                        + "' with status " + booking.getStatus()
                        + " (only CONFIRMED bookings can be marked no-show)");
            }
            booking.markNoShow();
            // Intentionally NOT freeing the seat.
        }
    }

    // ------------------------------------------------------------------
    // getBooking()
    // ------------------------------------------------------------------

    public Booking getBooking(String tripId, String bookingId) {
        Validator.requireNonBlank(tripId,    "tripId");
        Validator.requireNonBlank(bookingId, "bookingId");

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));
        Booking booking = trip.getBookings().get(bookingId);
        if (booking == null) throw new BookingNotFoundException(bookingId);
        return booking;
    }
}