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
 *   - Segment validation reads only immutable Route data -- no lock needed.
 *   - ALL mutations to a trip's booking state happen inside synchronized(trip).
 *   - Locking on the Trip instance means unrelated trips never contend.
 *
 * book():
 *   Validation outside the lock; check-then-assign inside -- prevents double-sell.
 *
 * cancel():
 *   Booking lookup AND status check are INSIDE synchronized(trip), not before it.
 *   Reason: if two threads call cancel() on the same booking concurrently, checking
 *   status outside the lock lets both threads pass the CONFIRMED check before either
 *   writes CANCELLED -- a double-free of the seat.  Inside the lock, the second
 *   thread sees CANCELLED and is rejected cleanly.
 *   After marking CANCELLED, promoteOne() is called in the same locked block so
 *   cancel+promote is one atomic unit (blueprint section 9).
 *
 * markNoShow():
 *   CONFIRMED -> NO_SHOW.  Seat is NOT freed -- no live clock in scope.
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
     * Returns a CONFIRMED Booking if a seat is free, or a WAITLISTED Booking
     * if no seat is available.  Never throws for lack of seats.
     *
     * @throws TripNotFoundException     if tripId is unknown
     * @throws com.shuttle.exception.InvalidSegmentException if stops are invalid
     */
    public Booking book(String tripId, String passengerId,
                        String fromStopId, String toStopId) {

        // Step 1: trip lookup (ConcurrentHashMap -- no lock)
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        // Step 2: validate before touching shared state (outside lock)
        int[] indices = Validator.validateSegment(trip, fromStopId, toStopId);
        Segment segment = new Segment(indices[0], indices[1]);

        // Steps 3-5: atomic check-then-act
        synchronized (trip) {

            // First-fit seat scan  O(seats * log k)
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

            // No free seat -- WAITLISTED + enqueue
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
     * The booking lookup AND status check both happen INSIDE synchronized(trip).
     * This closes the double-cancel race: if two threads arrive simultaneously,
     * the second sees status CANCELLED and is rejected -- no double-free of the seat.
     *
     * Cancel + promote is one atomic unit (blueprint section 9): no thread can
     * observe a freed seat without a simultaneous promotion attempt.
     *
     * @throws TripNotFoundException         if tripId is unknown
     * @throws BookingNotFoundException      if bookingId is not found on the trip
     * @throws InvalidBookingStateException  if booking is not in CONFIRMED status
     */
    public void cancel(String tripId, String bookingId) {

        // Trip lookup outside the lock (ConcurrentHashMap -- thread-safe)
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        synchronized (trip) {
            // Lookup AND validation INSIDE the lock -- see Javadoc above.
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

            // Free the seat in the TreeMap
            trip.getSeat(booking.getSeatNumber()).removeBooking(booking.getSegment());
            // Transition status
            booking.cancel();

            // Promote at most one waitlisted passenger -- still inside the lock
            // so cancel + promote is a single atomic operation.
            waitlistService.promoteOne(trip);
        }
    }

    // ------------------------------------------------------------------
    // markNoShow()
    // ------------------------------------------------------------------

    /**
     * Marks a CONFIRMED booking as NO_SHOW.
     *
     * The seat is NOT freed: we have no live clock concept in scope, so we
     * cannot know which portion of the segment has already been travelled.
     * The NO_SHOW status is recorded for reporting only.
     * (See README assumptions, blueprint section 11.)
     *
     * @throws TripNotFoundException         if tripId is unknown
     * @throws BookingNotFoundException      if bookingId is not found on the trip
     * @throws InvalidBookingStateException  if booking is not in CONFIRMED status
     */
    public void markNoShow(String tripId, String bookingId) {

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
            // Intentionally NOT calling seat.removeBooking() -- seat is kept occupied.
        }
    }

    // ------------------------------------------------------------------
    // getBooking() -- convenience lookup
    // ------------------------------------------------------------------

    public Booking getBooking(String tripId, String bookingId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));
        Booking booking = trip.getBookings().get(bookingId);
        if (booking == null) throw new BookingNotFoundException(bookingId);
        return booking;
    }
}