package com.shuttle.domain;

import java.time.LocalTime;
import java.util.Objects;

/**
 * A named stop on a route, together with its scheduled arrival time and its
 * zero-based sequence index within the route.
 *
 * sequenceIndex -- not arrivalTime -- is the ordering key used throughout the
 * overlap math (see Segment). arrivalTime is stored here because it is a
 * property of the stop's position in this route (A 10:00, B 10:15, ...).
 */
public class Stop {

    private final String id;
    private final String name;
    /** Zero-based position in the route ordered stop list. */
    private final int sequenceIndex;
    /** Scheduled arrival time at this stop for this route. */
    private final LocalTime arrivalTime;

    public Stop(String id, String name, int sequenceIndex, LocalTime arrivalTime) {
        this.id          = Objects.requireNonNull(id,          "id must not be null");
        this.name        = Objects.requireNonNull(name,        "name must not be null");
        this.arrivalTime = Objects.requireNonNull(arrivalTime, "arrivalTime must not be null");
        if (sequenceIndex < 0) {
            throw new IllegalArgumentException("sequenceIndex must be >= 0");
        }
        this.sequenceIndex = sequenceIndex;
    }

    public String getId()             { return id; }
    public String getName()           { return name; }
    public int getSequenceIndex()     { return sequenceIndex; }
    public LocalTime getArrivalTime() { return arrivalTime; }

    @Override
    public String toString() {
        return "Stop{id='" + id + "', name='" + name + "', seq=" + sequenceIndex
                + ", time=" + arrivalTime + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Stop other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}