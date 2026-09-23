package com.shuttle;

import com.shuttle.domain.Booking;
import com.shuttle.domain.BookingStatus;
import com.shuttle.domain.Route;
import com.shuttle.domain.Stop;
import com.shuttle.domain.Trip;
import com.shuttle.exception.ShuttleBookingException;
import com.shuttle.repository.TripRepository;
import com.shuttle.service.BookingService;
import com.shuttle.service.WaitlistService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static final String TRIP_ID = "T1";
    private static BookingService service;
    private static Scanner scanner;

    public static void main(String[] args) {
        setupSystem();
        scanner = new Scanner(System.in);

        boolean running = true;
        while (running) {
            System.out.println("==================================================");
            System.out.println("       OFFICE SHUTTLE BOOKING SYSTEM");
            System.out.println("==================================================");
            System.out.println("1. Login");
            System.out.println("2. Exit");
            System.out.print("\nEnter choice: ");

            int choice = readInt();

            switch (choice) {
                case 1:
                    loginFlow();
                    break;
                case 2:
                    System.out.println("Exiting system. Goodbye!");
                    running = false;
                    break;
                default:
                    System.out.println("Invalid option. Please try again.\n");
            }
        }
        scanner.close();
    }

    private static void loginFlow() {
        System.out.print("Enter Passenger ID: ");
        String passengerId = scanner.nextLine().trim();

        if (passengerId.isEmpty()) {
            System.out.println("Passenger ID cannot be empty.\n");
            return;
        }

        boolean loggedIn = true;
        while (loggedIn) {
            System.out.println("\n--------------------------------------------------");
            System.out.println("Welcome, " + passengerId + "!");
            System.out.println("\nRoute: A -> B -> C -> D");
            System.out.println("Trip : T1 (2 seats available)");
            System.out.println("--------------------------------------------------");
            System.out.println("1. Book Journey");
            System.out.println("2. Cancel Booking");
            System.out.println("3. View Booking");
            System.out.println("4. Mark No-Show");
            System.out.println("5. Logout");
            System.out.print("\nEnter choice: ");

            int choice = readInt();
            System.out.println(); // newline for formatting

            try {
                switch (choice) {
                    case 1:
                        bookJourney(passengerId);
                        break;
                    case 2:
                        cancelBooking();
                        break;
                    case 3:
                        viewBooking();
                        break;
                    case 4:
                        markNoShow();
                        break;
                    case 5:
                        System.out.println("Logging out...\n");
                        loggedIn = false;
                        break;
                    default:
                        System.out.println("Invalid option. Please try again.");
                }
            } catch (ShuttleBookingException e) {
                System.out.println("! ERROR: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("! UNEXPECTED ERROR: " + e.getMessage());
            }
        }
    }

    private static void bookJourney(String passengerId) {
        System.out.print("From Stop: ");
        String fromStop = scanner.nextLine().trim();
        System.out.print("To Stop: ");
        String toStop = scanner.nextLine().trim();

        Booking b = service.book(TRIP_ID, passengerId, fromStop, toStop);

        System.out.println();
        if (b.getStatus() == BookingStatus.CONFIRMED) {
            System.out.println("v BOOKING CONFIRMED");
        } else {
            System.out.println("! NO SEAT AVAILABLE");
        }
        
        System.out.println("\nBooking ID : " + b.getBookingId());
        System.out.println("Passenger  : " + b.getPassengerId());
        System.out.println("Journey    : " + fromStop + " -> " + toStop);
        if (b.getSeatNumber() != null) {
            System.out.println("Seat       : " + b.getSeatNumber());
        }
        System.out.println("Status     : " + b.getStatus());
    }

    private static void cancelBooking() {
        System.out.print("Enter Booking ID: ");
        String bookingId = scanner.nextLine().trim();

        Booking promoted = service.cancel(TRIP_ID, bookingId);
        
        System.out.println("\nv BOOKING CANCELLED");
        System.out.println("Booking " + bookingId + " has been successfully cancelled.");

        if (promoted != null) {
            System.out.println("\n*** WAITLIST PROMOTION ***");
            System.out.println("Passenger " + promoted.getPassengerId() + " (Booking " + promoted.getBookingId() + ") was automatically promoted to CONFIRMED on Seat " + promoted.getSeatNumber() + "!");
        }
    }

    private static void viewBooking() {
        System.out.print("Enter Booking ID: ");
        String bookingId = scanner.nextLine().trim();

        Booking b = service.getBooking(TRIP_ID, bookingId);

        System.out.println("\n--- Booking Details ---");
        System.out.println("Booking ID : " + b.getBookingId());
        System.out.println("Passenger  : " + b.getPassengerId());
        System.out.println("Journey    : Segment " + b.getSegment().getFromIdx() + " -> " + b.getSegment().getToIdx() + " (index-based)");
        System.out.println("Seat       : " + (b.getSeatNumber() == null ? "None" : b.getSeatNumber()));
        System.out.println("Status     : " + b.getStatus());
    }

    private static void markNoShow() {
        System.out.print("Enter Booking ID: ");
        String bookingId = scanner.nextLine().trim();

        service.markNoShow(TRIP_ID, bookingId);
        
        System.out.println("\nv MARKED AS NO-SHOW");
        System.out.println("Booking " + bookingId + " status updated to NO_SHOW.");
        System.out.println("Note: The seat remains occupied according to system rules.");
    }

    private static int readInt() {
        while (true) {
            try {
                String input = scanner.nextLine().trim();
                return Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.out.print("Invalid input. Please enter a number: ");
            }
        }
    }

    private static void setupSystem() {
        Stop stopA = new Stop("A", "Alpha", 0, LocalTime.of(10, 0));
        Stop stopB = new Stop("B", "Bravo", 1, LocalTime.of(10, 15));
        Stop stopC = new Stop("C", "Charlie", 2, LocalTime.of(10, 30));
        Stop stopD = new Stop("D", "Delta", 3, LocalTime.of(10, 45));

        Route route = new Route("R1", "City Loop", List.of(stopA, stopB, stopC, stopD));
        
        TripRepository repo = new TripRepository();
        WaitlistService waitlistService = new WaitlistService();
        service = new BookingService(repo, waitlistService);

        // Create a 2-seat trip as per the requirements
        Trip trip = new Trip(TRIP_ID, route, LocalDate.now(), 2);
        repo.save(trip);
    }
}