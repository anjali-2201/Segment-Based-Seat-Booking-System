package com.shuttle.domain;

import java.util.Objects;

/**
 * Value object representing a half-open interval [fromIdx, toIdx) of stop
 * sequence indices on a route.
 *
 * Half-open interval semantics: segments that merely touch at a stop (one ends
 * at index i, the next starts at index i) are NOT considered overlapping.
 *
 * Overlap formula (single source of truth -- lives here only):
 *   [Ys, Yd) overlaps [Xs, Xd)  iff  Ys < Xd  AND  Xs < Yd
 *   They do NOT overlap          iff  Yd <= Xs  OR   Ys >= Xd
 *
 * equals/hashCode are based on fromIdx/toIdx so that
 * Map<Segment, Deque<WaitlistEntry>> treats two identical segment requests
 * as the same key.
 */
public class Segment {

    /** Inclusive start stop index (zero-based). */
    private final int fromIdx;
    /** Exclusive end stop index. */
    private final int toIdx;

    public Segment(int fromIdx, int toIdx) {
        if (fromIdx >= toIdx) {
            throw new IllegalArgumentException(
                    "fromIdx (" + fromIdx + ") must be < toIdx (" + toIdx + ")");
        }
        this.fromIdx = fromIdx;
        this.toIdx   = toIdx;
    }

    public int getFromIdx() { return fromIdx; }
    public int getToIdx()   { return toIdx; }

    /**
     * Returns true if this segment overlaps other.
     *
     * Two segments overlap iff their road sections share at least one unit of
     * road -- touching only at a stop boundary does NOT count as overlap.
     */
    public boolean overlaps(Segment other) {
        // Ys < Xd  AND  Xs < Yd
        return this.fromIdx < other.toIdx && other.fromIdx < this.toIdx;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Segment other)) return false;
        return fromIdx == other.fromIdx && toIdx == other.toIdx;
    }

    @Override
    public int hashCode() { return Objects.hash(fromIdx, toIdx); }

    @Override
    public String toString() { return "Segment[" + fromIdx + ", " + toIdx + ")"; }
}