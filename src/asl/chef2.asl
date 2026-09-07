// chef2.asl
// Adaptive chef with atomic recipe booking, controlled preemption, and BDI recovery.

busy(chef2, false).
reputation(chef2, 89).
maxDailyDish(10).

expertise(pasta, 4).
expertise(pizza, 2).
expertise(salad, 3).
expertise(risotto, 4).
expertise(soup, 3).

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
        busy(chef2, false) & maxDailyDish(Max) & expertise(Dish, E) &
        successes(Dish, S) & failures(Dish, F) & reputation(chef2, R) <-
    .date(YY, MM, DD);
    .count(cooked(YY, MM, DD, Dish, _, _, _), N);
    !sendCookBid(OrderId, Dish, Priority, Waiter, N, Max, E, S, F, R).

+!cfpCook(OrderId, Customer, Dish, Priority, Waiter)[source(Waiter)] : busy(chef2, true) <-
    .send(Waiter, achieve, chefBid(OrderId, chef2, 999)).

+!sendCookBid(OrderId, Dish, Priority, Waiter, N, Max, E, S, F, R) : N < Max <-
    Score = (5 - E) * 10 + F * 8 - S * 2 + (100 - R) / 5;
    .print("chef2: bid score=", Score, " dish=", Dish, " priority=", Priority);
    .send(Waiter, achieve, chefBid(OrderId, chef2, Score)).

+!sendCookBid(OrderId, Dish, Priority, Waiter, N, Max, E, S, F, R) : N >= Max <-
    .print("chef2: daily limit reached for ", Dish);
    .send(Waiter, achieve, chefBid(OrderId, chef2, 999)).

+!cookAccepted(OrderId, Customer, Dish, Priority, Waiter)[source(Waiter)] : busy(chef2, false) <-
    .print("chef2: accepted ", Dish, " priority=", Priority, " for ", Customer);
    -busy(chef2, false);
    +busy(chef2, true);
    +pendingCook(OrderId, Customer, Dish, Dish, Priority, Waiter, original);
    !goTo(chef2, kitchen);
    reserveRecipe(Dish, chef2, OrderId, Priority).

+!cookAccepted(OrderId, Customer, Dish, Priority, Waiter)[source(Waiter)] : busy(chef2, true) <-
    .send(Waiter, achieve, chefDeclined(OrderId, chef2)).

+recipeReservationSuccess(OrderId, Dish, chef2) :
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) <-
    clearReservationSuccess(OrderId, Dish, chef2);
    +reservationOwned(OrderId, Dish);
    .print("chef2: recipe resources booked for ", Dish);
    .send(Waiter, achieve, recipeBooked(OrderId, Dish, chef2));
    !performCooking(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage).

// If equipment is already COOKING, this order waits and retries.
+recipeReservationFail(OrderId, Dish, chef2, resource_busy) :
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) <-
    clearReservationFail(OrderId, Dish, chef2, resource_busy);
    .print("chef2: ", Dish, " is waiting for a busy cooking resource");
    .wait(1200);
    reserveRecipe(Dish, chef2, OrderId, Priority).

// Out-of-stock or other booking failures trigger BDI recovery and an
// inventory-supported alternative.
+recipeReservationFail(OrderId, Dish, chef2, Reason) :
        Reason \== resource_busy &
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) <-
    clearReservationFail(OrderId, Dish, chef2, Reason);
    .print("chef2: booking failed for ", Dish, " reason=", Reason);
    recordReplan(chef2, OrderId, booking_failure);
    !beginRecovery(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage).

+reservationPreempted(OrderId, Dish, chef2, ByOrder, ByChef) :
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) <-
    clearReservationPreempted(OrderId, Dish, chef2, ByOrder, ByChef);
    -reservationOwned(OrderId, Dish);
    +preemptedBy(OrderId, ByOrder, ByChef);
    +staleIntention(OrderId, Dish, Stage);
    .print("chef2: reservation for ", Dish, " was preempted by ", ByChef,
           " for higher-priority ", ByOrder, "; replanning immediately");
    recordReplan(chef2, OrderId, preempted);
    !beginRecovery(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage).

+!performCooking(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) :
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) &
        reservationOwned(OrderId, Dish) & successes(Dish, S) & reputation(chef2, R) <-
    !deliberationDelay(Priority, Stage);
    ?reservationOwned(OrderId, Dish);
    startCooking(OrderId, chef2);
    .send(Waiter, achieve, cookingStarted(OrderId, Dish, chef2));
    prepareDish(Dish, OrderId, chef2);
    releaseRecipe(OrderId, chef2);
    .date(YY, MM, DD);
    .time(HH, NN, SS);
    +cooked(YY, MM, DD, Dish, HH, NN, SS);
    -successes(Dish, S);
    +successes(Dish, S + 1);
    -reputation(chef2, R);
    +reputation(chef2, R + 1);
    setReputation(chef2, R + 1);
    .send(Waiter, achieve, dishReady(OrderId, Dish, Customer));
    -reservationOwned(OrderId, Dish);
    -pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage);
    -busy(chef2, true);
    +busy(chef2, false);
    !goTo(chef2, start).

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
    .print("chef2: closed stale preempted intention for ", Dish, " / ", OrderId).

-!performCooking(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) :
        pendingCook(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) &
        not staleIntention(OrderId, Dish, Stage) & not recovering(OrderId) <-
    .print("chef2: cooking intention failed; reconsidering plan for ", OrderId);
    recordReplan(chef2, OrderId, intention_failure);
    !beginRecovery(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage).

+!beginRecovery(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) :
        not recovering(OrderId) & failures(Dish, F) & reputation(chef2, R) <-
    +recovering(OrderId);
    -reservationOwned(OrderId, Dish);
    releaseRecipe(OrderId, chef2);
    -failures(Dish, F);
    +failures(Dish, F + 1);
    -reputation(chef2, R);
    +reputation(chef2, R - 2);
    setReputation(chef2, R - 2);
    .send(inventoryManager, achieve,
          findAlternative(Dish, chef2, OrderId, Customer, Priority, Waiter)).

+!beginRecovery(OrderId, Customer, OriginalDish, Dish, Priority, Waiter, Stage) : recovering(OrderId) <- true.

+!cookAlternative(OrderId, Customer, AltDish, Priority, Waiter)[source(inventoryManager)] :
        AltDish \== none &
        pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage) <-
    +awaitingAlternativeApproval(OrderId, CurrentDish, AltDish);
    .print("chef2: feasible re-plan is ", AltDish, "; asking customer before committing");
    .send(Waiter, achieve,
          chefAlternativeProposal(OrderId, chef2, CurrentDish, AltDish)).

+!alternativeApproved(OrderId, AltDish)[source(Waiter)] :
        awaitingAlternativeApproval(OrderId, CurrentDish, AltDish) &
        pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage) <-
    -awaitingAlternativeApproval(OrderId, CurrentDish, AltDish);
    -pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage);
    +pendingCook(OrderId, Customer, OriginalDish, AltDish, Priority, Waiter, alternative);
    -recovering(OrderId);
    .print("chef2: customer approved ", AltDish, "; reserving its resources");
    reserveRecipe(AltDish, chef2, OrderId, Priority).

+!alternativeRejectedByCustomer(OrderId)[source(Waiter)] :
        awaitingAlternativeApproval(OrderId, CurrentDish, AltDish) &
        pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage) <-
    -awaitingAlternativeApproval(OrderId, CurrentDish, AltDish);
    -pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage);
    -recovering(OrderId);
    releaseRecipe(OrderId, chef2);
    -busy(chef2, true);
    +busy(chef2, false);
    .print("chef2: customer rejected the alternative; cooking task cancelled");
    !goTo(chef2, start).

+!cookAlternative(OrderId, Customer, none, Priority, Waiter)[source(inventoryManager)] :
        pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage) <-
    .print("chef2: no feasible alternative for ", OrderId);
    releaseRecipe(OrderId, chef2);
    .send(Waiter, achieve, cookingFailed(OrderId, chef2, no_alternative));
    -pendingCook(OrderId, Customer, OriginalDish, CurrentDish, Priority, Waiter, Stage);
    -recovering(OrderId);
    -busy(chef2, true);
    +busy(chef2, false);
    !goTo(chef2, start).

+!cookRejected(OrderId)[source(Waiter)] : true <-
    .print("chef2: ", OrderId, " assigned to another chef.").

+!goTo(chef2, Loc) : true <- move(chef2, Loc).
