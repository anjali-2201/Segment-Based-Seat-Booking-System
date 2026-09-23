# Office Shuttle Segment-Based Seat Booking

A Core Java + OOP implementation of a segmented shuttle booking system.

## Overview

This project implements an in-memory booking service for an office shuttle that allows multiple passengers to share the same physical seat as long as their travel segments do not overlap.

### Key Features
* **Segment-Based Seat Re-use:** A passenger travelling `A -> B` and another travelling `B -> D` can share the exact same seat on the same trip.
* **Per-Segment Waitlist:** If a trip is full, bookings are placed in a `WAITLISTED` status. They are held in a per-segment FIFO queue.
* **Automatic Promotion:** When a confirmed booking is cancelled, the system automatically promotes the earliest-arriving eligible waitlisted passenger to `CONFIRMED`.
* **Thread-Safe:** Designed to handle highly concurrent booking and cancellation requests without race conditions (e.g., double-selling a seat).
* **Strict Validation:** Guard clauses protect the system from invalid segments, unknown entities, and illegal state transitions.

---

## Tech Stack & Rules Followed

* **Java 17** (Core Java + OOP)
* **JUnit 5** (Tests)
* **No Spring Boot / No Database** (In-memory `ConcurrentHashMap` storage)
* Minimal dependencies (`pom.xml` contains only JUnit).

---

## Getting Started

### Prerequisites
* Java 17 or higher
* Maven 3.6+

### Build and Test
To compile the code and run all 142 unit and concurrency tests:
```bash
mvn clean test
```

### Run the Interactive Application
An interactive console application (`Main.java`) is provided to simulate and demonstrate passenger flows in real time.
To execute it using Maven:
```bash
mvn exec:java -Dexec.mainClass="com.shuttle.Main"
```

*(Alternatively, run `Main.java` directly via your IDE or with `java -cp out/classes com.shuttle.Main`).*

#### Interactive Console Flow
1. **Login:** Simple passenger login by ID (e.g., `P101`).
2. **Menu Options:**
   * **1. Book Journey:** Enter From and To stops (e.g., `A` to `C`). The system allocates a seat or places the passenger on the waitlist, displaying derived journey times.
   * **2. Cancel Booking:** Enter Booking ID to cancel a confirmed reservation. Automatically promotes eligible waitlisted passengers.
   * **3. View Booking:** Look up booking details (passenger, journey with route timings, seat, status).
   * **4. Mark No-Show:** Marks the booking as `NO_SHOW` (seat remains occupied).
   * **5. Logout / Exit:** Switch passenger profiles or terminate the session.

---

## Core Design & Architecture

### 1. Domain Model
* **Trip & Route:** A `Trip` occurs on a `Route`. The `Route` has an ordered list of `Stop`s.
* **Segment:** A mathematical half-open interval `[fromIndex, toIndex)`. Overlap logic is centralized here. `A -> B` (0 to 1) and `B -> D` (1 to 3) do *not* overlap because the first ends exactly where the second begins.
* **Seat:** Contains a `TreeMap<Integer, Booking>` keyed by `fromIndex`. Checking if a seat is free for a new segment is an efficient **O(log k)** operation using `floorEntry` and `ceilingEntry`.

### 2. Threading & Concurrency Strategy
* **`synchronized(trip)` monitor:** All booking mutations (checking seat availability + assigning the seat) happen inside a `synchronized(trip)` block.
* **Why Trip-Level?** Locking at the `Seat` level introduces a dangerous check-then-act race across multiple seats. Locking at the `Trip` level guarantees that two concurrent threads trying to book the final available seat will not double-sell it.
* **Performance:** Trips are independent. Bookings on Trip 1 do not block bookings on Trip 2.

### 3. Waitlist Design
* **Per-Segment Queues:** The `WaitlistService` maintains a `Map<Segment, Deque<WaitlistEntry>>`. Passengers requesting identical segments share a queue.
* **Cross-Queue Fairness:** Every waitlisted entry is tagged with a globally incrementing `insertionSequence`. When a cancellation frees a seat, the system peeks at the head of every non-empty queue, filters out segments that still don't fit, and promotes the one with the lowest sequence number (absolute earliest arrival).
* **Atomic Cancel + Promote:** Cancellation and waitlist promotion occur within the exact same `synchronized(trip)` block. No thread can observe a "free" seat if there is an eligible waitlisted passenger waiting for it.

### 4. Assumptions & Edge Cases Handled
* **No-Show Does Not Free the Seat:** `markNoShow()` transitions the status but leaves the seat occupied. Because this system has no "live GPS/clock", it cannot know if the passenger will board at a later stop.
* **Waitlist is a Status, Not an Exception:** Overbooking is a normal business flow. Rejecting overlapping bookings returns a `WAITLISTED` booking rather than throwing a `NoSeatAvailableException`. Exceptions are reserved for truly invalid states (e.g., `InvalidSegmentException` for travelling backwards).

### 5. Scheduled Route Timings & Journey Derivation
* **Stop Timetable:** The shuttle route follows a defined schedule:
  * `A` = `10:00`
  * `B` = `10:15`
  * `C` = `10:30`
  * `D` = `10:45`
* **Derived Journey Times:** Journey times are automatically derived from the selected boarding and alighting stops (e.g., choosing `A -> C` derives `A -> C | 10:00 -> 10:30`). Both the route overview and booking confirmation/details display these timings.
* **Index/Segment-Based Core Logic:** All seat allocation, conflict detection, and seat reuse algorithms remain strictly index/segment-based (`[fromIdx, toIdx)`). Arrival times are stored as domain metadata on `Stop` and do not alter or complicate the underlying interval math.