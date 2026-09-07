// waiter2.asl
// Adaptive waiter: distance, experience, failures, and chef Contract Net.

available(waiter2, true).
completedTasks(0).
failedTasks(0).
distance(table1, 5).
distance(table2, 2).

+!cfp(OrderId, Customer, Table, Dish, Priority, Reputation)[source(restaurantManager)] :
        available(waiter2, true) & completedTasks(C) & failedTasks(F) & distance(Table, D) <-
    Score = D + F * 6 - C + (100 - Reputation) / 10;
    .print("waiter2: bid score=", Score, " distance=", D, " priority=", Priority);
    .send(restaurantManager, achieve, propose(OrderId, waiter2, Score)).

+!cfp(OrderId, Customer, Table, Dish, Priority, Reputation)[source(restaurantManager)] :
        available(waiter2, false) <-
    .send(restaurantManager, achieve, propose(OrderId, waiter2, 999)).

+!acceptOrder(OrderId, Customer, Table, Dish, Priority)[source(restaurantManager)] :
        available(waiter2, true) <-
    .print("waiter2: accepted ", OrderId, " priority=", Priority);
    -available(waiter2, true);
    +available(waiter2, false);
    +pendingOrder(OrderId, Customer, Table, Dish, Priority);
    .send(restaurantManager, achieve, orderAccepted(OrderId, waiter2));
    !goTo(waiter2, Table);
    .send(inventoryManager, achieve, checkDish(Dish, waiter2, OrderId)).

+!acceptOrder(OrderId, Customer, Table, Dish, Priority)[source(restaurantManager)] :
        available(waiter2, false) <-
    .print("waiter2: cannot accept ", OrderId, " because another task is still active");
    .send(restaurantManager, achieve, orderDeclined(OrderId, waiter2)).

+!rejectOrder(OrderId)[source(restaurantManager)] : true <-
    .print("waiter2: ", OrderId, " assigned to another waiter.").

// CHEF CONTRACT NET

+!dishAvailable(Dish, OrderId)[source(inventoryManager)] :
        pendingOrder(OrderId, Customer, Table, Dish, Priority) <-
    +selectingChef(OrderId);
    .print("waiter2: requesting adaptive chef bids for ", Dish);
    .send(chef1, achieve, cfpCook(OrderId, Customer, Dish, Priority, waiter2));
    .send(chef2, achieve, cfpCook(OrderId, Customer, Dish, Priority, waiter2));
    .wait(800);
    !chooseChef(OrderId).

+!dishUnavailable(Dish, Missing, none, OrderId)[source(inventoryManager)] :
        pendingOrder(OrderId, Customer, Table, Dish, Priority) <-
    .print("waiter2: ", Dish, " unavailable; no feasible alternative. Missing=", Missing);
    setTableOrder(Table, Dish, unavailable);
    .send(Customer, tell, orderUnavailable(Customer, Dish, Missing));
    -pendingOrder(OrderId, Customer, Table, Dish, Priority);
    -available(waiter2, false);
    +available(waiter2, true);
    .send(restaurantManager, achieve, waiterOutcome(waiter2, failure));
    .send(restaurantManager, achieve, waiterFree(waiter2));
    !goTo(waiter2, start).

+!dishUnavailable(Dish, Missing, AltDish, OrderId)[source(inventoryManager)] :
        AltDish \== none & pendingOrder(OrderId, Customer, Table, Dish, Priority) <-
    .print("waiter2: proposing ", AltDish, " because ", Dish, " is unavailable; missing ", Missing);
    +pendingAlternative(OrderId, stock, Dish, AltDish);
    setTableOrder(Table, Dish, awaiting_customer);
    .send(Customer, achieve,
          considerAlternative(OrderId, Dish, AltDish, stock, Missing, waiter2)).

// The customer, not the waiter, commits an inventory alternative.
+!alternativeAccepted(OrderId, OriginalDish, AltDish, stock)[source(Customer)] :
        pendingAlternative(OrderId, stock, OriginalDish, AltDish) &
        pendingOrder(OrderId, Customer, Table, OriginalDish, Priority) <-
    -pendingAlternative(OrderId, stock, OriginalDish, AltDish);
    -pendingOrder(OrderId, Customer, Table, OriginalDish, Priority);
    +pendingOrder(OrderId, Customer, Table, AltDish, Priority);
    .print("waiter2: customer accepted stock alternative ", AltDish);
    setTableOrder(Table, AltDish, alternative);
    .send(inventoryManager, achieve, checkDish(AltDish, waiter2, OrderId)).

+!alternativeRejected(OrderId, OriginalDish, AltDish, stock)[source(Customer)] :
        pendingAlternative(OrderId, stock, OriginalDish, AltDish) &
        pendingOrder(OrderId, Customer, Table, OriginalDish, Priority) <-
    -pendingAlternative(OrderId, stock, OriginalDish, AltDish);
    setTableOrder(Table, OriginalDish, rejected);
    -pendingOrder(OrderId, Customer, Table, OriginalDish, Priority);
    -available(waiter2, false);
    +available(waiter2, true);
    .send(restaurantManager, achieve, waiterFree(waiter2));
    !goTo(waiter2, start).

// A chef that loses a BOOKED resource asks the customer before changing dishes.
+!chefAlternativeProposal(OrderId, Chef, OldDish, AltDish)[source(Chef)] :
        pendingOrder(OrderId, Customer, Table, OriginalDish, Priority) <-
    +pendingAlternative(OrderId, replan, OldDish, AltDish);
    setTableOrder(Table, OldDish, awaiting_customer);
    .print("waiter2: asking ", Customer, " to approve chef re-plan ", OldDish, " -> ", AltDish);
    .send(Customer, achieve,
          considerAlternative(OrderId, OldDish, AltDish, replan, [resource_conflict], waiter2)).

+!alternativeAccepted(OrderId, OldDish, AltDish, replan)[source(Customer)] :
        pendingAlternative(OrderId, replan, OldDish, AltDish) &
        selectedChef(OrderId, Chef) &
        pendingOrder(OrderId, Customer, Table, OriginalDish, Priority) <-
    -pendingAlternative(OrderId, replan, OldDish, AltDish);
    setTableOrder(Table, AltDish, replanned);
    .print("waiter2: customer approved chef re-plan to ", AltDish);
    .send(Chef, achieve, alternativeApproved(OrderId, AltDish)).

+!alternativeRejected(OrderId, OldDish, AltDish, replan)[source(Customer)] :
        pendingAlternative(OrderId, replan, OldDish, AltDish) &
        selectedChef(OrderId, Chef) &
        pendingOrder(OrderId, Customer, Table, OriginalDish, Priority) <-
    -pendingAlternative(OrderId, replan, OldDish, AltDish);
    .print("waiter2: customer rejected chef alternative ", AltDish);
    .send(Chef, achieve, alternativeRejectedByCustomer(OrderId));
    setTableOrder(Table, OldDish, unavailable);
    -pendingOrder(OrderId, Customer, Table, OriginalDish, Priority);
    -selectedChef(OrderId, Chef);
    -available(waiter2, false);
    +available(waiter2, true);
    .send(restaurantManager, achieve, waiterFree(waiter2));
    !goTo(waiter2, start).

+!chefBid(OrderId, Chef, Score)[source(Chef)] :
        selectingChef(OrderId) & chefOffer(OrderId, Chef, OldScore) <-
    -chefOffer(OrderId, Chef, OldScore);
    +chefOffer(OrderId, Chef, Score);
    .print("waiter2: updated chef bid ", Score, " from ", Chef).

+!chefBid(OrderId, Chef, Score)[source(Chef)] :
        selectingChef(OrderId) & not chefOffer(OrderId, Chef, OldScore) <-
    +chefOffer(OrderId, Chef, Score);
    .print("waiter2: chef bid ", Score, " from ", Chef).

+!chefBid(OrderId, Chef, Score)[source(Chef)] : not selectingChef(OrderId) <-
    .print("waiter2: ignoring late chef bid from ", Chef).

+!chooseChef(OrderId) :
        selectingChef(OrderId) & pendingOrder(OrderId, Customer, Table, Dish, Priority) &
        chefOffer(OrderId, chef1, B1) & chefOffer(OrderId, chef2, B2) &
        B1 < 999 & B1 <= B2 <-
    -selectingChef(OrderId);
    +selectedChef(OrderId, chef1);
    .print("waiter2: selecting chef1 score=", B1);
    setTableOrder(Table, Dish, assigned);
    .send(chef1, achieve, cookAccepted(OrderId, Customer, Dish, Priority, waiter2));
    .send(chef2, achieve, cookRejected(OrderId));
    !goTo(waiter2, start).

+!chooseChef(OrderId) :
        selectingChef(OrderId) & pendingOrder(OrderId, Customer, Table, Dish, Priority) &
        chefOffer(OrderId, chef1, B1) & chefOffer(OrderId, chef2, B2) &
        B2 < 999 & B2 < B1 <-
    -selectingChef(OrderId);
    +selectedChef(OrderId, chef2);
    .print("waiter2: selecting chef2 score=", B2);
    setTableOrder(Table, Dish, assigned);
    .send(chef2, achieve, cookAccepted(OrderId, Customer, Dish, Priority, waiter2));
    .send(chef1, achieve, cookRejected(OrderId));
    !goTo(waiter2, start).

+!chooseChef(OrderId) : selectingChef(OrderId) & pendingOrder(OrderId, Customer, Table, Dish, Priority) <-
    .print("waiter2: no chef available; reopening negotiation for ", OrderId);
    .send(Customer, tell, coordinationDelay(Customer, chefs_busy));
    .wait(1200);
    .send(chef1, achieve, cfpCook(OrderId, Customer, Dish, Priority, waiter2));
    .send(chef2, achieve, cfpCook(OrderId, Customer, Dish, Priority, waiter2));
    .wait(800);
    !chooseChef(OrderId).

+!chefDeclined(OrderId, Chef)[source(Chef)] :
        selectedChef(OrderId, Chef) & pendingOrder(OrderId, Customer, Table, Dish, Priority) <-
    -selectedChef(OrderId, Chef);
    +selectingChef(OrderId);
    .send(Customer, tell, coordinationDelay(Customer, chef_reassignment));
    .send(chef1, achieve, cfpCook(OrderId, Customer, Dish, Priority, waiter2));
    .send(chef2, achieve, cfpCook(OrderId, Customer, Dish, Priority, waiter2));
    .wait(800);
    !chooseChef(OrderId).

// ORDER LIFECYCLE SYNC
// The table ticket follows the real kitchen state. Selecting a chef does not
// mean cooking has started: resources are first BOOKED and only become
// COOKING after startCooking succeeds in the environment.
+!recipeBooked(OrderId, Dish, Chef)[source(Chef)] :
        pendingOrder(OrderId, Customer, Table, CurrentDish, Priority) <-
    setTableOrder(Table, Dish, booked).

+!cookingStarted(OrderId, Dish, Chef)[source(Chef)] :
        pendingOrder(OrderId, Customer, Table, CurrentDish, Priority) <-
    setTableOrder(Table, Dish, cooking).

// DELIVERY WITH EXPLICIT GOAL-FAILURE RECOVERY

+!dishReady(OrderId, Dish, Customer)[source(Chef)] :
        pendingOrder(OrderId, Customer, Table, OriginalDish, Priority) <-
    setTableOrder(Table, Dish, ready);
    !deliverOrder(OrderId, Customer, Table, OriginalDish, Dish, Chef).

+!deliverOrder(OrderId, Customer, Table, OriginalDish, Dish, Chef) :
        pendingOrder(OrderId, Customer, Table, OriginalDish, Priority) & completedTasks(C) <-
    .print("waiter2: collecting ", Dish, " from ", Chef);
    !goTo(waiter2, kitchen);
    pickupDish(Dish);
    !goTo(waiter2, Table);
    serveDish(Dish, Table);
    .send(Customer, tell, orderServed(Customer, Dish));
    -pendingOrder(OrderId, Customer, Table, OriginalDish, Priority);
    -selectedChef(OrderId, Chef);
    -available(waiter2, false);
    +available(waiter2, true);
    -+completedTasks(C + 1);
    .send(restaurantManager, achieve, waiterOutcome(waiter2, success));
    .send(restaurantManager, achieve, waiterFree(waiter2));
    !goTo(waiter2, start).

-!deliverOrder(OrderId, Customer, Table, OriginalDish, Dish, Chef) :
        pendingOrder(OrderId, Customer, Table, OriginalDish, Priority) & failedTasks(F) <-
    -+failedTasks(F + 1);
    .print("waiter2: delivery intention failed; re-planning ", OrderId);
    .send(Customer, tell, coordinationDelay(Customer, delivery_replan));
    .send(restaurantManager, achieve, waiterOutcome(waiter2, failure));
    .wait(1000);
    .send(inventoryManager, achieve, checkDish(OriginalDish, waiter2, OrderId)).

+!cookingFailed(OrderId, Chef, no_alternative)[source(Chef)] :
        pendingOrder(OrderId, Customer, Table, Dish, Priority) <-
    -selectedChef(OrderId, Chef);
    setTableOrder(Table, Dish, unavailable);
    .send(Customer, tell, orderUnavailable(Customer, Dish, [coordination_failure]));
    -pendingOrder(OrderId, Customer, Table, Dish, Priority);
    -available(waiter2, false);
    +available(waiter2, true);
    .send(restaurantManager, achieve, waiterOutcome(waiter2, failure));
    .send(restaurantManager, achieve, waiterFree(waiter2));
    !goTo(waiter2, start).

// EVENT-DRIVEN SUPPLY TASK WITH STALE-REQUEST CANCELLATION

+!handleSupply(Customer, Table, Item)[source(restaurantManager)] : available(waiter2, true) <-
    -available(waiter2, true);
    +available(waiter2, false);
    +supplyTaskActive(Customer, Table, Item);
    !deliverSupplyTask(Customer, Table, Item).

+!handleSupply(Customer, Table, Item)[source(restaurantManager)] : available(waiter2, false) <-
    .print("waiter2: supply task deferred because another local task is active");
    .send(restaurantManager, achieve, supplyDeferred(waiter2, Customer, Table, Item)).

+!cancelSupply(Customer, Table, Item)[source(restaurantManager)] :
        supplyTaskActive(Customer, Table, Item) <-
    -supplyTaskActive(Customer, Table, Item);
    +supplyCancelledByManager(Customer, Table, Item);
    .print("waiter2: cancelling stale ", Item, " task for ", Customer).

+!cancelSupply(Customer, Table, Item)[source(restaurantManager)] :
        not supplyTaskActive(Customer, Table, Item) <- true.

+!deliverSupplyTask(Customer, Table, Item) : supplyTaskActive(Customer, Table, Item) <-
    .send(inventoryManager, achieve, checkSupply(Item, waiter2, Customer, Table)).

+!supplyOk(Item, Customer, Table)[source(inventoryManager)] :
        supplyTaskActive(Customer, Table, Item) & completedTasks(C) <-
    +supplyReserved(Customer, Table, Item);
    !completeSupplyDelivery(Customer, Table, Item, C).

// The supply was reserved by inventory after the customer had already left.
+!supplyOk(Item, Customer, Table)[source(inventoryManager)] :
        not supplyTaskActive(Customer, Table, Item) <-
    returnSupply(Item);
    .send(inventoryManager, achieve, restockSupply(Item));
    !clearSupplyTaskFlags(Customer, Table, Item);
    -available(waiter2, false);
    +available(waiter2, true);
    .send(restaurantManager, achieve, supplyCancelled(waiter2, Customer, Table, Item));
    .send(restaurantManager, achieve, waiterFree(waiter2));
    !goTo(waiter2, start).

+!completeSupplyDelivery(Customer, Table, Item, C) :
        supplyTaskActive(Customer, Table, Item) &
        supplyReserved(Customer, Table, Item) <-
    ?supplyTaskActive(Customer, Table, Item);
    !goTo(waiter2, storage);
    ?supplyTaskActive(Customer, Table, Item);
    takeSupply(Item);
    +carryingSupply(Customer, Table, Item);
    !goTo(waiter2, Table);
    ?supplyTaskActive(Customer, Table, Item);
    deliverSupply(Item, Table, Customer);
    -carryingSupply(Customer, Table, Item);
    -supplyReserved(Customer, Table, Item);
    -supplyTaskActive(Customer, Table, Item);
    .send(Customer, tell, supplyDelivered(Customer, Item));
    -available(waiter2, false);
    +available(waiter2, true);
    -+completedTasks(C + 1);
    .send(restaurantManager, achieve,
          supplyCompleted(waiter2, Customer, Table, Item));
    .send(restaurantManager, achieve, waiterOutcome(waiter2, success));
    .send(restaurantManager, achieve, waiterFree(waiter2));
    !goTo(waiter2, start).

// Any failure after inventory reservation returns the object to storage.
// This includes cancellation while travelling and the model's atomic stale-
// delivery check rejecting an item at an empty/leaving table.
-!completeSupplyDelivery(Customer, Table, Item, C) :
        supplyReserved(Customer, Table, Item) <-
    returnSupply(Item);
    .send(inventoryManager, achieve, restockSupply(Item));
    !clearSupplyTaskFlags(Customer, Table, Item);
    -available(waiter2, false);
    +available(waiter2, true);
    .print("waiter2: returned cancelled ", Item, " to storage");
    .send(restaurantManager, achieve,
          supplyCancelled(waiter2, Customer, Table, Item));
    .send(restaurantManager, achieve, waiterFree(waiter2));
    !goTo(waiter2, start).

+!supplyUnavailable(Item, Customer, Table)[source(inventoryManager)] : true <-
    !clearSupplyTaskFlags(Customer, Table, Item);
    .send(Customer, tell, supplyUnavailable(Customer, Item));
    -available(waiter2, false);
    +available(waiter2, true);
    .send(restaurantManager, achieve,
          supplyCancelled(waiter2, Customer, Table, Item));
    .send(restaurantManager, achieve, waiterFree(waiter2)).

-!deliverSupplyTask(Customer, Table, Item) : failedTasks(F) <-
    -+failedTasks(F + 1);
    !clearSupplyTaskFlags(Customer, Table, Item);
    -available(waiter2, false);
    +available(waiter2, true);
    .send(restaurantManager, achieve,
          supplyCancelled(waiter2, Customer, Table, Item));
    .send(restaurantManager, achieve, waiterFree(waiter2)).

// Idempotent cleanup: remove only beliefs that are actually present.
+!clearSupplyTaskFlags(Customer, Table, Item) :
        carryingSupply(Customer, Table, Item) <-
    -carryingSupply(Customer, Table, Item);
    !clearSupplyTaskFlags(Customer, Table, Item).

+!clearSupplyTaskFlags(Customer, Table, Item) :
        supplyReserved(Customer, Table, Item) <-
    -supplyReserved(Customer, Table, Item);
    !clearSupplyTaskFlags(Customer, Table, Item).

+!clearSupplyTaskFlags(Customer, Table, Item) :
        supplyTaskActive(Customer, Table, Item) <-
    -supplyTaskActive(Customer, Table, Item);
    !clearSupplyTaskFlags(Customer, Table, Item).

+!clearSupplyTaskFlags(Customer, Table, Item) :
        supplyCancelledByManager(Customer, Table, Item) <-
    -supplyCancelledByManager(Customer, Table, Item);
    !clearSupplyTaskFlags(Customer, Table, Item).

+!clearSupplyTaskFlags(Customer, Table, Item) : true <- true.


// TABLE CLEANING TASK
// A table is not reusable immediately after payment. The manager assigns a
// free waiter, who walks to the table, spends time cleaning it, and only then
// reports that the table is ready for the next customer.
+!cleanTableTask(Table)[source(restaurantManager)] : available(waiter2, true) <-
    -available(waiter2, true);
    +available(waiter2, false);
    .print("waiter2: going to clean ", Table);
    !goTo(waiter2, Table);
    .wait(1400);
    reclaimReusableSupply(Table, cutlery);
    cleanTable(Table, waiter2);
    .print("waiter2: finished cleaning ", Table);
    .send(restaurantManager, achieve, tableCleaned(waiter2, Table));
    -available(waiter2, false);
    +available(waiter2, true);
    .send(restaurantManager, achieve, waiterFree(waiter2));
    !goTo(waiter2, start).

+!cleanTableTask(Table)[source(restaurantManager)] : available(waiter2, false) <-
    .print("waiter2: cleaning task deferred because waiter is busy");
    .send(restaurantManager, achieve, cleaningDeferred(waiter2, Table)).

+!goTo(waiter2, Loc) : true <- move(waiter2, Loc).
