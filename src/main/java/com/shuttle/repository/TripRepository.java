package com.shuttle.repository;

import com.shuttle.domain.Trip;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for Trip instances, keyed by tripId.
 *
 * ConcurrentHashMap makes trip lookup (findById) thread-safe without extra
 * locking. Booking state *within* a trip is guarded by synchronized(trip)
 * in the service layer -- the repository only owns trip-level CRUD.
 *
 * No separate SeatRepository: seats are owned by and accessed through Trip
 * (Trip.getSeat()), so a second repository would just be an indirect,
 * redundant path to the same data.
 */
public class TripRepository {

    private final ConcurrentHashMap<String, Trip> store = new ConcurrentHashMap<>();

    /** Persists (or replaces) a trip. */
    public void save(Trip trip) {
        store.put(trip.getId(), trip);
    }

    /** Returns the trip for the given id, or empty if not found. */
    public Optional<Trip> findById(String tripId) {
        return Optional.ofNullable(store.get(tripId));
    }

    /** Removes a trip. Used in tests; not part of the core booking flow. */
    public void delete(String tripId) {
        store.remove(tripId);
    }

    /** Returns an unmodifiable view of all stored trips. */
    public Collection<Trip> findAll() {
        return Collections.unmodifiableCollection(store.values());
    }

    /** Returns the number of trips currently stored. */
    public int size() {
        return store.size();
    }
}