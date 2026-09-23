package com.shuttle.domain;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An ordered sequence of Stops that defines a shuttle route.
 *
 * Stops are stored in route order; their sequenceIndex values must match their
 * position in this list (index 0 = first stop, index n-1 = last stop).
 */
public class Route {

    private final String id;
    private final String name;
    /** Immutable, ordered list of stops (index in list == stop.sequenceIndex). */
    private final List<Stop> stops;

    public Route(String id, String name, List<Stop> stops) {
        this.id   = Objects.requireNonNull(id,   "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (stops == null || stops.isEmpty()) {
            throw new IllegalArgumentException("A route must have at least one stop");
        }
        this.stops = Collections.unmodifiableList(List.copyOf(stops));
    }

    public String getId()        { return id; }
    public String getName()      { return name; }
    /** Returns an unmodifiable view of the stops in route order. */
    public List<Stop> getStops() { return stops; }

    /**
     * Returns the zero-based sequence index of stop in this route,
     * or -1 if the stop is not part of this route.
     */
    public int indexOf(Stop stop) {
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).equals(stop)) return i;
        }
        return -1;
    }

    /** Returns true if a stop with the given id is part of this route. */
    public boolean containsStopId(String stopId) {
        return stops.stream().anyMatch(s -> s.getId().equals(stopId));
    }

    /** Returns the Stop for the given id, or null if not found. */
    public Stop getStopById(String stopId) {
        return stops.stream()
                    .filter(s -> s.getId().equals(stopId))
                    .findFirst()
                    .orElse(null);
    }

    @Override
    public String toString() {
        return "Route{id='" + id + "', name='" + name + "', stops=" + stops + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Route other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}