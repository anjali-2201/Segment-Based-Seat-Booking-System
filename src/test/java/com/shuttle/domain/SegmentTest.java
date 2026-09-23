package com.shuttle.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive tests for Segment.overlaps() -- the single source of truth for
 * the overlap formula described in blueprint section 6.
 *
 * All segments use half-open [fromIdx, toIdx) semantics:
 *   - Ys < Xd  AND  Xs < Yd  => overlap
 *   - Yd <= Xs  OR  Ys >= Xd => no overlap
 *
 * Stop layout used in comments:  A=0  B=1  C=2  D=3  E=4
 */
@DisplayName("Segment overlap logic")
class SegmentTest {

    // ---------------------------------------------------------------
    // Blueprint section 6: explicit boundary cases
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Touching at one stop is NOT overlap: [0,1) vs [1,3)")
    void touchingAtOneStop_noOverlap() {
        // A->B then B->D: end of first == start of second
        Segment ab = new Segment(0, 1);
        Segment bd = new Segment(1, 3);
        assertFalse(ab.overlaps(bd), "A->B and B->D share only stop B; not an overlap");
        assertFalse(bd.overlaps(ab), "Overlap must be symmetric");
    }

    @Test
    @DisplayName("Overlapping mid-route: [0,2) vs [1,3)")
    void overlappingMidRoute() {
        // A->C and B->D share the B->C road section
        Segment ac = new Segment(0, 2);
        Segment bd = new Segment(1, 3);
        assertTrue(ac.overlaps(bd), "A->C and B->D share road B->C");
        assertTrue(bd.overlaps(ac), "Overlap must be symmetric");
    }

    @Test
    @DisplayName("Identical segments overlap")
    void identicalSegmentsOverlap() {
        Segment s1 = new Segment(1, 3);
        Segment s2 = new Segment(1, 3);
        assertTrue(s1.overlaps(s2), "Same segment must overlap itself");
    }

    @Test
    @DisplayName("One segment fully contains the other")
    void containmentOverlap() {
        // A->E contains B->C
        Segment ae = new Segment(0, 4);
        Segment bc = new Segment(1, 2);
        assertTrue(ae.overlaps(bc));
        assertTrue(bc.overlaps(ae));
    }

    @Test
    @DisplayName("Completely disjoint segments: [0,1) vs [2,4)")
    void completelyDisjoint() {
        // A->B and C->E: gap of one stop between them
        Segment ab = new Segment(0, 1);
        Segment ce = new Segment(2, 4);
        assertFalse(ab.overlaps(ce));
        assertFalse(ce.overlaps(ab));
    }

    @Test
    @DisplayName("Adjacent (touching at end/start) in reverse: [1,3) vs [3,4)")
    void touchingAtEnd_noOverlap() {
        Segment bd = new Segment(1, 3);
        Segment de = new Segment(3, 4);
        assertFalse(bd.overlaps(de));
        assertFalse(de.overlaps(bd));
    }

    @Test
    @DisplayName("Partial overlap: second starts inside first -- [0,3) vs [2,4)")
    void partialOverlapSecondStartsInsideFirst() {
        Segment ac = new Segment(0, 3);  // A->D
        Segment cd = new Segment(2, 4);  // C->E
        assertTrue(ac.overlaps(cd));
        assertTrue(cd.overlaps(ac));
    }

    @Test
    @DisplayName("Same start, different end -- [1,2) vs [1,4)")
    void sameStartDifferentEnd() {
        Segment s1 = new Segment(1, 2);
        Segment s2 = new Segment(1, 4);
        assertTrue(s1.overlaps(s2));
        assertTrue(s2.overlaps(s1));
    }

    @Test
    @DisplayName("Same end, different start -- [0,3) vs [2,3)")
    void sameEndDifferentStart() {
        Segment s1 = new Segment(0, 3);
        Segment s2 = new Segment(2, 3);
        assertTrue(s1.overlaps(s2));
        assertTrue(s2.overlaps(s1));
    }

    // ---------------------------------------------------------------
    // Segment constructor validation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Constructor rejects fromIdx == toIdx")
    void constructor_rejectsEqualIndices() {
        assertThrows(IllegalArgumentException.class, () -> new Segment(2, 2));
    }

    @Test
    @DisplayName("Constructor rejects fromIdx > toIdx")
    void constructor_rejectsReversedIndices() {
        assertThrows(IllegalArgumentException.class, () -> new Segment(3, 1));
    }

    // ---------------------------------------------------------------
    // equals / hashCode (required for Map<Segment, Deque<...>>)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Equal segments have equal hashCode")
    void equalsAndHashCode() {
        Segment s1 = new Segment(1, 3);
        Segment s2 = new Segment(1, 3);
        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
    }

    @Test
    @DisplayName("Different segments are not equal")
    void notEqual() {
        assertNotEquals(new Segment(1, 3), new Segment(1, 4));
        assertNotEquals(new Segment(1, 3), new Segment(0, 3));
    }
}