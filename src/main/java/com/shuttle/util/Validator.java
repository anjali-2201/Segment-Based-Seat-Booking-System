package com.shuttle.util;

import com.shuttle.domain.Route;
import com.shuttle.domain.Stop;
import com.shuttle.domain.Trip;
import com.shuttle.exception.InvalidSegmentException;

/**
 * Stateless validation helpers called at the service boundary, BEFORE any
 * shared state is touched or any lock is acquired.
 *
 * Validation outside synchronized(trip) means invalid requests are rejected
 * cheaply without contending on the trip monitor.
 *
 * All violations throw a ShuttleBookingException subclass so callers can use
 * a single catch clause for uniform error handling.
 */
public final class Validator {

    private Validator() {}

    /**
     * Checks that none of the booking request parameters are null or blank.
     * Must be called BEFORE the trip lookup so callers never see a confusing
     * NullPointerException from ConcurrentHashMap.get(null).
     *
     * @throws InvalidSegmentException if any parameter is null or blank
     */
    public static void validateBookingInputs(String tripId, String passengerId,
                                             String fromStopId, String toStopId) {
        requireNonBlank(tripId,      "tripId");
        requireNonBlank(passengerId, "passengerId");
        requireNonBlank(fromStopId,  "fromStopId");
        requireNonBlank(toStopId,    "toStopId");
    }

    /**
     * Validates that fromStopId and toStopId:
     *   1. Both exist on the trip's route.
     *   2. fromStop.sequenceIndex < toStop.sequenceIndex
     *      (same stop or reversed order are both rejected).
     *
     * Returns int[]{fromIdx, toIdx} on success.
     *
     * @throws InvalidSegmentException on any violation
     */
    public static int[] validateSegment(Trip trip, String fromStopId, String toStopId) {
        Route route = trip.getRoute();

        Stop fromStop = route.getStopById(fromStopId);
        if (fromStop == null) {
            throw new InvalidSegmentException(
                    "Stop '" + fromStopId + "' is not on route '" + route.getId() + "'");
        }

        Stop toStop = route.getStopById(toStopId);
        if (toStop == null) {
            throw new InvalidSegmentException(
                    "Stop '" + toStopId + "' is not on route '" + route.getId() + "'");
        }

        int fromIdx = fromStop.getSequenceIndex();
        int toIdx   = toStop.getSequenceIndex();

        if (fromIdx >= toIdx) {
            throw new InvalidSegmentException(
                    "fromStop '" + fromStopId + "' (index " + fromIdx + ") must be"
                    + " strictly before toStop '" + toStopId
                    + "' (index " + toIdx + ") in route order."
                    + " Same stop and reversed direction are both invalid.");
        }

        return new int[]{fromIdx, toIdx};
    }

    /**
     * Validates a single String parameter: must not be null or blank.
     * Throws InvalidSegmentException (a ShuttleBookingException) so callers
     * receive a typed domain exception rather than a raw NullPointerException.
     */
    public static void requireNonBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new InvalidSegmentException(
                    "'" + paramName + "' must not be null or blank");
        }
    }
}