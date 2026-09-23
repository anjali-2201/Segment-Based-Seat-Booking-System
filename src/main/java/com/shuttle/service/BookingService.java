package com.shuttle.service;

import com.shuttle.domain.Booking;
import com.shuttle.domain.BookingStatus;
import com.shuttle.domain.Seat;
import com.shuttle.domain.Segment;
import com.shuttle.domain.Trip;
import com.shuttle.exception.BookingNotFoundException;
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
 *   - ALL mutations to a trip's booking state (seat assignment, status changes,
 *     waitlist mutations) happen inside synchronized(trip).
 *   - Locking on the Trip instance means unrelated trips never contend.
 *
 * Availability queries (isFree) are also inside the lock so that
 * check-then-act is atomic -- preventing the double-sell race (section 8).
 *
 * Phase 3 scope: book() happy path + WAITLISTED stub when no seat is free.
 * Phase 4 will wire the WAITLISTED path into the real waitlist queue.
 * Phase 5 will add cancel() and markNoShow().
 */
public class BookingService {

    private final TripRepository tripRepository;

    public BookingService(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    // ------------------------------------------------------------------
    // book()
    // ------------------------------------------------------------------

    /**
     * Books a segment on a trip for a passenger.
     *
     * Flow:
     *   1. Look up trip (thread-safe ConcurrentHashMap read).
     *   2. Validate stops and segment direction (reads immutable Route -- no lock).
     *   3. Enter synchronized(trip).
     *   4. Scan seats 1..N; first seat where isFree(segment) == true is assigned.
     *   5a. Seat found  -> create CONFIRMED Booking, insert into seat's TreeMap,
     *                      register in trip.bookings, return.
     *   5b. No seat     -> create WAITLISTED Booking (no seat assigned),
     *                      register in trip.bookings, return.
     *                      (Phase 4 adds the actual waitlist enqueue here.)
     *
     * @param tripId      identifies the trip
     * @param passengerId identifies the passenger
     * @param fromStopId  id of the boarding stop
     * @param toStopId    id of the alighting stop
     * @return            a CONFIRMED or WAITLISTED Booking
     * @throws TripNotFoundException    if tripId is unknown
     * @throws com.shuttle.exception.InvalidSegmentException if stops are invalid or out of order
     */
    public Booking book(String tripId, String passengerId,
                        String fromStopId, String toStopId) {

        // Step 1: trip lookup (ConcurrentHashMap -- no lock needed)
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        // Step 2: validate before touching any shared state
        int[] indices = Validator.validateSegment(trip, fromStopId, toStopId);
        Segment segment = new Segment(indices[0], indices[1]);

        // Step 3-5: atomic check-then-act
        synchronized (trip) {
            // Step 4: find first free seat (O(seats * log k))
            for (int seatNum = 1; seatNum <= trip.getTotalSeats(); seatNum++) {
                Seat seat = trip.getSeat(seatNum);
                if (seat.isFree(segment)) {
                    // Step 5a: seat found -- CONFIRMED
                    String bookingId = UUID.randomUUID().toString();
                    Booking booking  = new Booking(
                            bookingId, tripId, passengerId, segment,
                            seatNum, BookingStatus.CONFIRMED);
                    seat.addBooking(booking);
                    trip.getBookings().put(bookingId, booking);
                    return booking;
                }
            }

            // Step 5b: no free seat -- WAITLISTED
            // Phase 4 will enqueue into trip.waitlistBySegment here.
            String bookingId = UUID.randomUUID().toString();
            Booking booking  = new Booking(
                    bookingId, tripId, passengerId, segment,
                    null, BookingStatus.WAITLISTED);
            trip.getBookings().put(bookingId, booking);
            return booking;
        }
    }

    // ------------------------------------------------------------------
    // getBooking() -- convenience lookup, used by tests and later phases
    // ------------------------------------------------------------------

    /**
     * Looks up a booking by id across all trips stored in the repository.
     * For single-trip lookups prefer passing the trip directly.
     *
     * Phase 5 (cancellation) looks up by bookingId directly from trip.bookings
     * inside the synchronized block, so this method is a convenience helper
     * for tests and the demo runner.
     */
    public Booking getBooking(String tripId, String bookingId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));
        Booking booking = trip.getBookings().get(bookingId);
        if (booking == null) {
            throw new BookingNotFoundException(bookingId);
        }
        return booking;
    }
}