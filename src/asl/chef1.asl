// chef1.asl
// Adaptive chef with atomic recipe booking, controlled preemption, and BDI recovery.

busy(chef1, false).
reputation(chef1, 91).
maxDailyDish(10).

expertise(pasta, 2).
expertise(pizza, 5).
expertise(salad, 4).
expertise(risotto, 3).
expertise(soup, 4).

successes(pasta, 0).
successes(pizza, 0).
successes(salad, 0).
successes(risotto, 0).
successes(soup, 0).
failures(pasta, 0).
failures(pizza, 0).
failures(salad, 0).
failures(risotto, 0).
failures(soup, 0).

+!cfpCook(OrderId, Customer, Dish, Priority, Waiter)[source(Waiter)] :
        busy(chef1, false) & maxDailyDish(Max) & expertise(Dish, E) &
        successes(Dish, S) & failures(Dish, F) & reputation(chef1, R) <-
    .date(YY, MM, DD);
    .count(cooked(YY, MM, DD, Dish, _, _, _), N);
    !sendCookBid(OrderId, Dish, Priority, Waiter, N, Max, E, S, F, R).

+!cfpCook(OrderId, Customer, Dish, Priority, Waiter)[source(Waiter)] : busy(chef1, true) <-
    .send(Waiter, achieve, chefBid(OrderId, chef1, 999)).

+!sendCookBid(OrderId, Dish, Priority, Waiter, N, Max, E, S, F, R) : N < Max <-
    Score = (5 - E) * 10 + F * 8 - S * 2 + (100 - R) / 5;
    .print("chef1: bid score=", Score, " dish=", Dish, " priority=", Priority);
    .send(Waiter, achieve, chefBid(OrderId, chef1, Score)).

+!sendCookBid(OrderId, Dish, Priority, Waiter, N, Max, E, S, F, R) : N >= Max <-
    .print("chef1: daily limit reached for ", Dish);
    .send(Waiter, achieve, chefBid(OrderId, chef1, 999)).

+!cookAccepted(OrderId, Customer, Dish, Priority, Waiter)[source(Waiter)] : busy(chef1, false) <-
    .print("chef1: accepted ", Dish, " priority=", Priority, " for ", Customer);
    -busy(chef1, false);
    +busy(chef1, true);
    +pendingCook(OrderId, Customer, Dish, Dish, Priority, Waiter, original);
    !goTo(chef1, kitchen);
    reserveRecipe(Dish, chef1, OrderId, Priority).

+!cookAccepted(OrderId, Customer, Dish, Priority, Waiter)[source(Waiter)] : busy(chef1, true) <-
    .send(Waiter, achieve, chefDeclined(OrderId, chef1)).

// ENVIRONMENT BOOKING EVENTS

+recipeReservationSuccess(OrderId, Dish, chef1) :
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) <-
    clearReservationSuccess(OrderId, Dish, chef1);
    +reservationOwned(OrderId, Dish);
    .print("chef1: recipe resources booked for ", Dish);
    .send(Waiter, achieve, recipeBooked(OrderId, Dish, chef1));
    !performCooking(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage).

// If equipment is already COOKING, this order waits and retries.
+recipeReservationFail(OrderId, Dish, chef1, resource_busy) :
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) <-
    clearReservationFail(OrderId, Dish, chef1, resource_busy);
    .print("chef1: ", Dish, " is waiting for a busy cooking resource");
    .wait(1200);
    reserveRecipe(Dish, chef1, OrderId, Priority).

// Out-of-stock or other booking failures trigger BDI recovery and an
// inventory-supported alternative.
+recipeReservationFail(OrderId, Dish, chef1, Reason) :
        Reason \== resource_busy &
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) <-
    clearReservationFail(OrderId, Dish, chef1, Reason);
    .print("chef1: booking failed for ", Dish, " reason=", Reason);
    recordReplan(chef1, OrderId, booking_failure);
    !beginRecovery(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage).

+reservationPreempted(OrderId, Dish, chef1, ByOrder, ByChef) :
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) <-
    clearReservationPreempted(OrderId, Dish, chef1, ByOrder, ByChef);
    -reservationOwned(OrderId, Dish);
    +preemptedBy(OrderId, ByOrder, ByChef);
    +staleIntention(OrderId, Dish, Stage);
    .print("chef1: reservation for ", Dish, " was preempted by ", ByChef,
           " for higher-priority ", ByOrder, "; replanning immediately");
    recordReplan(chef1, OrderId, preempted);
    !beginRecovery(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage).

// COOKING INTENTION AND EXPLICIT FAILURE RECOVERY

+!performCooking(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) :
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) &
        reservationOwned(OrderId, Dish) & successes(Dish, S) & reputation(chef1, R) <-
    // Priority-aware deliberation: normal bookings remain preemptible; urgent jobs commit quickly.
    !deliberationDelay(Priority, Stage);
    ?reservationOwned(OrderId, Dish);
    startCooking(OrderId, chef1);
    .send(Waiter, achieve, cookingStarted(OrderId, Dish, chef1));
    prepareDish(Dish, OrderId, chef1);
    releaseRecipe(OrderId, chef1);
    .date(YY, MM, DD);
    .time(HH, NN, SS);
    +cooked(YY, MM, DD, Dish, HH, NN, SS);
    -successes(Dish, S);
    +successes(Dish, S + 1);
    -reputation(chef1, R);
    +reputation(chef1, R + 1);
    setReputation(chef1, R + 1);
    .send(Waiter, achieve, dishReady(OrderId, Dish, Customer));
    -reservationOwned(OrderId, Dish);
    -pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage);
    -busy(chef1, true);
    +busy(chef1, false);
    !goTo(chef1, start).

+!deliberationDelay(Priority, alternative) : true <-
    .wait(500).

+!deliberationDelay(Priority, original) : Priority < 8 <-
    .wait(6000).

+!deliberationDelay(Priority, original) : Priority >= 8 <-
    .wait(500).

// If this exact intention was invalidated by a preemption event, close only
// that stale intention.  A newly approved alternative remains independent.
-!performCooking(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) :
        staleIntention(OrderId, Dish, Stage) <-
    -staleIntention(OrderId, Dish, Stage);
    .print("chef1: closed stale preempted intention for ", Dish, " / ", OrderId).

-!performCooking(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) :
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) &
        not staleIntention(OrderId, Dish, Stage) & not recovering(OrderId) <-
    .print("chef1: cooking intention failed; reconsidering plan for ", OrderId);
    recordReplan(chef1, OrderId, intention_failure);
    !beginRecovery(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage).

+!beginRecovery(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) :
        not recovering(OrderId) & failures(Dish, F) & reputation(chef1, R) <-
    +recovering(OrderId);
    -reservationOwned(OrderId, Dish);
    releaseRecipe(OrderId, chef1);
    -failures(Dish, F);
    +failures(Dish, F + 1);
    -reputation(chef1, R);
    +reputation(chef1, R - 2);
    setReputation(chef1, R - 2);
    .send(inventoryManager, achieve,
          findAlternative(Dish, chef1, OrderId, Customer, Priority, Waiter)).

+!beginRecovery(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) : recovering(OrderId) <-
    true.

+!cookAlternative(OrderId, Customer, AltDish, Priority, Waiter)[source(inventoryManager)] :
        AltDish \== none &
        pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage) <-
    +awaitingAlternativeApproval(OrderId, CurrentDish, AltDish);
    .print("chef1: feasible re-plan is ", AltDish, "; asking customer before committing");
    .send(Waiter, achieve,
          chefAlternativeProposal(OrderId, chef1, CurrentDish, AltDish)).

+!alternativeApproved(OrderId, AltDish)[source(Waiter)] :
        awaitingAlternativeApproval(OrderId, CurrentDish, AltDish) &
        pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage) <-
    -awaitingAlternativeApproval(OrderId, CurrentDish, AltDish);
    -pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage);
    +pendingCook(OrderId, Customer, OriginalDish, AltDish, Priority, Waiter, alternative);
    -recovering(OrderId);
    .print("chef1: customer approved ", AltDish, "; reserving its resources");
    reserveRecipe(AltDish, chef1, OrderId, Priority).

+!alternativeRejectedByCustomer(OrderId)[source(Waiter)] :
        awaitingAlternativeApproval(OrderId, CurrentDish, AltDish) &
        pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage) <-
    -awaitingAlternativeApproval(OrderId, CurrentDish, AltDish);
    -pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage);
    -recovering(OrderId);
    releaseRecipe(OrderId, chef1);
    -busy(chef1, true);
    +busy(chef1, false);
    .print("chef1: customer rejected the alternative; cooking task cancelled");
    !goTo(chef1, start).

+!cookAlternative(OrderId, Customer, none, Priority, Waiter)[source(inventoryManager)] :
        pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage) <-
    .print("chef1: no feasible alternative for ", OrderId);
    releaseRecipe(OrderId, chef1);
    .send(Waiter, achieve, cookingFailed(OrderId, chef1, no_alternative));
    -pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage);
    -recovering(OrderId);
    -busy(chef1, true);
    +busy(chef1, false);
    !goTo(chef1, start).

+!cookRejected(OrderId)[source(Waiter)] : true <-
    .print("chef1: ", OrderId, " assigned to another chef.").

+!goTo(chef1, Loc) : true <- move(chef1, Loc).
