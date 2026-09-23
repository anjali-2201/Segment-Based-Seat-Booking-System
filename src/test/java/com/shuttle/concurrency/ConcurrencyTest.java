package com.shuttle.concurrency;

import com.shuttle.domain.Booking;
import com.shuttle.domain.BookingStatus;
import com.shuttle.domain.Route;
import com.shuttle.domain.Stop;
import com.shuttle.domain.Trip;
import com.shuttle.repository.TripRepository;
import com.shuttle.service.BookingService;
import com.shuttle.service.WaitlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for BookingService.
 *
 * The locking strategy (synchronized(trip) wrapping check-then-assign) is in
 * place from Phase 1. These tests prove it prevents the double-sell race that
 * the assignment PDF calls out as the primary concurrency hazard.
 *
 * Pattern:
 *   1. Create a trip with N seats (often N=1 for the hardest case).
 *   2. Use a CountDownLatch to hold all threads at the starting gate.
 *   3. Release them simultaneously -- maximum contention.
 *   4. Assert exactly N CONFIRMED bookings; all others are WAITLISTED.
 *
 * Tests are @RepeatedTest(5) to expose races that only surface intermittently.
 *
 * Why synchronized(trip) and not per-seat locking:
 *   A booking decision spans "check all seats", so per-seat locking risks a
 *   check-then-book race across seats: thread A reads seat1=free and seat2=free,
 *   thread B does the same, both pick seat1. Trip-level locking prevents this
 *   entirely at the cost of slightly coarser granularity -- acceptable because
 *   one trip has low concurrency (one bus, one crowd at a time).
 */
@DisplayName("Concurrency -- synchronized(trip) prevents double-sell")
class ConcurrencyTest {

    private Stop stopA, stopB, stopC, stopD;
    private Route route;
    private TripRepository repo;
    private BookingService service;

    @BeforeEach
    void setUp() {
        stopA = new Stop("A", "Alpha",   0, LocalTime.of(10, 0));
        stopB = new Stop("B", "Bravo",   1, LocalTime.of(10, 15));
        stopC = new Stop("C", "Charlie", 2, LocalTime.of(10, 30));
        stopD = new Stop("D", "Delta",   3, LocalTime.of(10, 45));

        route   = new Route("R1", "City Loop", List.of(stopA, stopB, stopC, stopD));
        repo    = new TripRepository();
        service = new BookingService(repo, new WaitlistService());
    }

    private Trip trip(String id, int seats) {
        Trip t = new Trip(id, route, LocalDate.of(2026, 9, 23), seats);
        repo.save(t);
        return t;
    }

    // ---------------------------------------------------------------
    // Core race test: 1 seat, N threads -- exactly 1 CONFIRMED
    // ---------------------------------------------------------------

    /**
     * The single most interview-relevant test (blueprint section 13).
     *
     * 20 threads all race to book the same segment on a 1-seat trip.
     * Exactly one must succeed (CONFIRMED); the other 19 must be WAITLISTED.
     * Run 5 times to expose intermittent races.
     */
    @RepeatedTest(5)
    @DisplayName("1 seat, 20 threads racing: exactly 1 CONFIRMED, 19 WAITLISTED")
    void oneSeat_manyThreads_exactlyOneConfirmed() throws InterruptedException {
        trip("RACE1", 1);
        int threadCount = 20;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<Booking> results = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int pid = i;
            pool.submit(() -> {
                try {
                    startGate.await();   // hold until all threads are ready
                    Booking b = service.book("RACE1", "P" + pid, "A", "D");
                    results.add(b);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();  // release the starting gate -- maximum contention
        assertTrue(done.await(10, TimeUnit.SECONDS), "Threads did not finish in time");
        pool.shutdown();

        assertEquals(threadCount, results.size(), "Every thread must get a booking");

        long confirmed   = results.stream().filter(b -> b.getStatus() == BookingStatus.CONFIRMED).count();
        long waitlisted  = results.stream().filter(b -> b.getStatus() == BookingStatus.WAITLISTED).count();

        assertEquals(1,               confirmed,  "Exactly 1 seat -> exactly 1 CONFIRMED");
        assertEquals(threadCount - 1, waitlisted, "All other threads must be WAITLISTED");
    }

    /**
     * Generalised: K seats, N threads (N > K).
     * Exactly K threads get CONFIRMED; N-K are WAITLISTED.
     */
    @RepeatedTest(5)
    @DisplayName("3 seats, 15 threads racing: exactly 3 CONFIRMED, 12 WAITLISTED")
    void threeSeats_manyThreads_exactlyThreeConfirmed() throws InterruptedException {
        int seats       = 3;
        int threadCount = 15;
        trip("RACE2", seats);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<Booking> results = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int pid = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    Booking b = service.book("RACE2", "P" + pid, "A", "D");
                    results.add(b);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        long confirmed  = results.stream().filter(b -> b.getStatus() == BookingStatus.CONFIRMED).count();
        long waitlisted = results.stream().filter(b -> b.getStatus() == BookingStatus.WAITLISTED).count();

        assertEquals(seats,                  confirmed);
        assertEquals(threadCount - seats,    waitlisted);
    }

    // ---------------------------------------------------------------
    // No duplicate seat assignments
    // ---------------------------------------------------------------

    @RepeatedTest(5)
    @DisplayName("No two CONFIRMED bookings share the same seat for the same segment")
    void noTwoConfirmedBookingsShareSeat() throws InterruptedException {
        int seats       = 3;
        int threadCount = 30;
        trip("RACE3", seats);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<Booking> results = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int pid = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    results.add(service.book("RACE3", "P" + pid, "A", "D"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        // Collect seat numbers of all CONFIRMED bookings
        List<Integer> seatNums = results.stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED)
                .map(Booking::getSeatNumber)
                .toList();

        // All seat numbers must be distinct
        long distinctSeats = seatNums.stream().distinct().count();
        assertEquals(seats, distinctSeats,
                "Each physical seat must be assigned at most once for the same segment");
    }

    // ---------------------------------------------------------------
    // Concurrent cancellations -- no double-free
    // ---------------------------------------------------------------

    @RepeatedTest(5)
    @DisplayName("Concurrent cancels on the same booking: exactly one succeeds, rest throw")
    void concurrentCancel_exactlyOneSucceeds() throws InterruptedException {
        trip("RACE4", 1);
        Booking b = service.book("RACE4", "P1", "A", "D");
        assertEquals(BookingStatus.CONFIRMED, b.getStatus());

        int threadCount = 10;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(threadCount);
        AtomicInteger successes  = new AtomicInteger(0);
        AtomicInteger failures   = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    service.cancel("RACE4", b.getBookingId());
                    successes.incrementAndGet();
                } catch (com.shuttle.exception.InvalidBookingStateException
                         | com.shuttle.exception.BookingNotFoundException ex) {
                    failures.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1,               successes.get(), "Exactly one cancel must succeed");
        assertEquals(threadCount - 1, failures.get(),  "All other cancels must throw");
        assertEquals(BookingStatus.CANCELLED, b.getStatus());
    }

    // ---------------------------------------------------------------
    // Independent trips do not contend
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Concurrent bookings on different trips do not interfere")
    void differentTrips_noInterference() throws InterruptedException {
        int tripsCount  = 10;
        int threadCount = tripsCount;

        for (int i = 0; i < tripsCount; i++) {
            trip("INDEP" + i, 1);
        }

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<Booking> results = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final String tripId = "INDEP" + i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    results.add(service.book(tripId, "P1", "A", "D"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        // Every trip has 1 seat and 1 thread -> all must be CONFIRMED
        long confirmed = results.stream().filter(b -> b.getStatus() == BookingStatus.CONFIRMED).count();
        assertEquals(tripsCount, confirmed,
                "Each trip's single seat must be booked without interference from other trips");
    }
}