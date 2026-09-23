package com.shuttle;

import com.shuttle.domain.Booking;
import com.shuttle.domain.Route;
import com.shuttle.domain.Stop;
import com.shuttle.domain.Trip;
import com.shuttle.repository.TripRepository;
import com.shuttle.service.BookingService;
import com.shuttle.service.WaitlistService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("  Office Shuttle Segment-Based Seat Booking Demo  ");
        System.out.println("==================================================\n");

        // 1. Setup Data
        Stop stopA = new Stop("A", "Alpha", 0, LocalTime.of(10, 0));
        Stop stopB = new Stop("B", "Bravo", 1, LocalTime.of(10, 15));
        Stop stopC = new Stop("C", "Charlie", 2, LocalTime.of(10, 30));
        Stop stopD = new Stop("D", "Delta", 3, LocalTime.of(10, 45));

        Route route = new Route("R1", "City Loop", List.of(stopA, stopB, stopC, stopD));
        
        TripRepository repo = new TripRepository();
        WaitlistService waitlistService = new WaitlistService();
        BookingService service = new BookingService(repo, waitlistService);

        // Create a 2-seat trip
        Trip trip = new Trip("T1", route, LocalDate.now(), 2);
        repo.save(trip);

        System.out.println("Trip 'T1' created on Route A -> B -> C -> D with 2 seats.\n");

        // --- Scenario 1: Basic Booking and Seat Reuse ---
        System.out.println("--- Scenario 1: Seat Reuse (Non-overlapping) ---");
        Booking b1 = service.book("T1", "Pass-1", "A", "B");
        System.out.println("Pass-1 books A->B: Status=" + b1.getStatus() + ", Seat=" + b1.getSeatNumber());

        Booking b2 = service.book("T1", "Pass-2", "B", "D");
        System.out.println("Pass-2 books B->D: Status=" + b2.getStatus() + ", Seat=" + b2.getSeatNumber());
        System.out.println("Notice Pass-1 and Pass-2 share Seat 1 because their segments do not overlap.\n");

        // --- Scenario 2: Overlapping and Waitlist ---
        System.out.println("--- Scenario 2: Overlapping causes Waitlist ---");
        Booking b3 = service.book("T1", "Pass-3", "A", "C");
        System.out.println("Pass-3 books A->C: Status=" + b3.getStatus() + ", Seat=" + b3.getSeatNumber());
        
        Booking b4 = service.book("T1", "Pass-4", "A", "D");
        System.out.println("Pass-4 books A->D: Status=" + b4.getStatus() + ", Seat=" + (b4.getSeatNumber() == null ? "None" : b4.getSeatNumber()));
        System.out.println("Notice Pass-4 is WAITLISTED because Seat 1 is occupied A->D, and Seat 2 is occupied A->C.\n");

        // --- Scenario 3: Cancellation and Promotion ---
        System.out.println("--- Scenario 3: Cancellation & Waitlist Promotion ---");
        System.out.println("Cancelling Pass-3's booking (A->C on Seat 2)...");
        service.cancel("T1", b3.getBookingId());
        
        Booking b4Updated = service.getBooking("T1", b4.getBookingId());
        System.out.println("Pass-4 status is now: " + b4Updated.getStatus() + ", Seat=" + b4Updated.getSeatNumber());
        System.out.println("Notice Pass-4 was automatically promoted to CONFIRMED on Seat 2!\n");

        // --- Scenario 4: Validation and No-Show ---
        System.out.println("--- Scenario 4: Mark No-Show ---");
        service.markNoShow("T1", b2.getBookingId());
        Booking b2Updated = service.getBooking("T1", b2.getBookingId());
        System.out.println("Pass-2 marked as NO_SHOW. Status is now: " + b2Updated.getStatus());
        System.out.println("Seat 1 remains occupied for B->D because a no-show does not free the seat mid-route.\n");

        System.out.println("Demo completed successfully.");
    }
}