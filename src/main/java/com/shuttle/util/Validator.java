package com.shuttle.util;

import com.shuttle.domain.Route;
import com.shuttle.domain.Stop;
import com.shuttle.domain.Trip;
import com.shuttle.exception.InvalidSegmentException;
import com.shuttle.exception.TripNotFoundException;

/**
 * Stateless validation helpers called at the service boundary, BEFORE any
 * shared state is touched or any lock is acquired.
 *
 * Keeping validation outside synchronized(trip) means invalid requests are
 * rejected cheaply without contending on the trip monitor.
 */
public final class Validator {

    private Validator() {}

    /**
     * Validates that fromStopId and toStopId:
     *   1. Both exist on the trip's route.
     *   2. fromStop.sequenceIndex < toStop.sequenceIndex
     *      (same stop or reversed order are both rejected).
     *
     * Returns an int[]{fromIdx, toIdx} on success.
     * Throws InvalidSegmentException on any violation.
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
                    "fromStop '" + fromStopId + "' (index " + fromIdx + ") must be before"
                    + " toStop '" + toStopId + "' (index " + toIdx + ") in route order");
        }

        return new int[]{fromIdx, toIdx};
    }
}