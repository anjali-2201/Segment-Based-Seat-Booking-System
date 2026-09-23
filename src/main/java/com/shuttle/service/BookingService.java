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
 * Availability check and seat assignment are inside the same synchronized block
 * so that check-then-act is atomic, preventing the double-sell race (section 8).
 *
 * Phase 3: book() happy path.
 * Phase 4: WAITLISTED path wired into WaitlistService.enqueue().
 * Phase 5: cancel() + markNoShow() added here.
 */
public class BookingService {

    private final TripRepository tripRepository;
    private final WaitlistService waitlistService;

    /** Full constructor -- prefer this for explicit dependency injection. */
    public BookingService(TripRepository tripRepository, WaitlistService waitlistService) {
        this.tripRepository  = tripRepository;
        this.waitlistService = waitlistService;
    }

    /**
     * Convenience constructor that creates a default WaitlistService.
     * Existing Phase-3 tests that only test the happy path use this form.
     */
    public BookingService(TripRepository tripRepository) {
        this(tripRepository, new WaitlistService());
    }

    // ------------------------------------------------------------------
    // book()
    // ------------------------------------------------------------------

    /**
     * Books a segment on a trip for a passenger.
     *
     * Flow:
     *   1. Look up trip (thread-safe ConcurrentHashMap read -- no lock).
     *   2. Validate stops and segment direction (reads immutable Route -- no lock).
     *   3. Enter synchronized(trip).
     *   4. Scan seats 1..N; first seat where isFree(segment) is assigned.
     *   5a. Seat found  -> CONFIRMED Booking; insert into seat TreeMap + trip.bookings.
     *   5b. No seat     -> WAITLISTED Booking; register in trip.bookings;
     *                      enqueue WaitlistEntry via WaitlistService.
     *
     * @param tripId      identifies the trip
     * @param passengerId identifies the passenger
     * @param fromStopId  id of the boarding stop
     * @param toStopId    id of the alighting stop
     * @return            a CONFIRMED or WAITLISTED Booking -- never null, never throws
     *                    for lack of seats
     * @throws TripNotFoundException     if tripId is unknown
     * @throws com.shuttle.exception.InvalidSegmentException if stops are invalid
     */
    public Booking book(String tripId, String passengerId,
                        String fromStopId, String toStopId) {

        // Step 1: trip lookup (no lock -- ConcurrentHashMap)
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        // Step 2: validate before touching shared state (outside the lock)
        int[] indices = Validator.validateSegment(trip, fromStopId, toStopId);
        Segment segment = new Segment(indices[0], indices[1]);

        // Steps 3-5: atomic check-then-act
        synchronized (trip) {

            // Step 4: first-fit seat scan  O(seats * log k)
            for (int seatNum = 1; seatNum <= trip.getTotalSeats(); seatNum++) {
                Seat seat = trip.getSeat(seatNum);
                if (seat.isFree(segment)) {
                    // Step 5a: free seat found -- CONFIRMED
                    String bookingId = UUID.randomUUID().toString();
                    Booking booking  = new Booking(
                            bookingId, tripId, passengerId, segment,
                            seatNum, BookingStatus.CONFIRMED);
                    seat.addBooking(booking);
                    trip.getBookings().put(bookingId, booking);
                    return booking;
                }
            }

            // Step 5b: no free seat -- WAITLISTED + enqueue
            String bookingId = UUID.randomUUID().toString();
            Booking booking  = new Booking(
                    bookingId, tripId, passengerId, segment,
                    null, BookingStatus.WAITLISTED);
            trip.getBookings().put(bookingId, booking);
            waitlistService.enqueue(trip, booking);   // <-- Phase 4 wire-up
            return booking;
        }
    }

    // ------------------------------------------------------------------
    // getBooking() -- convenience lookup used by tests and the demo runner
    // ------------------------------------------------------------------

    public Booking getBooking(String tripId, String bookingId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));
        Booking booking = trip.getBookings().get(bookingId);
        if (booking == null) throw new BookingNotFoundException(bookingId);
        return booking;
    }
}