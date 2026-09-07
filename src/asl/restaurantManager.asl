// restaurantManager.asl
// Coordinates tables, adaptive waiter selection, reputation, and feedback.

tableState(table1, free).
tableState(table2, free).
waiterState(waiter1, free).
waiterState(waiter2, free).
waiterReputation(waiter1, 90).
waiterReputation(waiter2, 88).

!register.

+!register : true <-
    .my_name(Me);
    .print("Manager started: ", Me, " (adaptive coordination enabled)");
    setReputation(waiter1, 90);
    setReputation(waiter2, 88).

// TABLE MANAGEMENT
// Tables become DIRTY after a customer leaves. They are not reusable until
// a waiter physically visits the table and completes a cleaning task.
// Customers that arrive while both tables are unavailable are queued once;
// the manager seats them immediately after a table has been cleaned.

+!requestTable(Customer)[source(Customer)] :
        tableState(table1, free) & not seatingInProgress(Customer, AnyTable) <-
    // Reserve the BDI table before the slow walking action.  This closes the
    // window in which another request could also see table1 as free.
    -tableState(table1, free);
    +tableState(table1, reserved(Customer));
    +seatingInProgress(Customer, table1);
    .print("manager: reserving table1 for ", Customer);
    !completeSeating(Customer, table1).

+!requestTable(Customer)[source(Customer)] :
        not tableState(table1, free) & tableState(table2, free) &
        not seatingInProgress(Customer, AnyTable) <-
    -tableState(table2, free);
    +tableState(table2, reserved(Customer));
    +seatingInProgress(Customer, table2);
    .print("manager: reserving table2 for ", Customer);
    !completeSeating(Customer, table2).

+!requestTable(Customer)[source(Customer)] :
        not tableState(table1, free) & not tableState(table2, free) &
        not waitingForTable(Customer) <-
    +waitingForTable(Customer);
    .print("manager: both tables unavailable; queued ", Customer);
    .send(Customer, tell, tableQueued(Customer)).

+!requestTable(Customer)[source(Customer)] :
        seatingInProgress(Customer, Table) <-
    .print("manager: duplicate table request ignored; ", Customer,
           " is already being seated at ", Table).

+!requestTable(Customer)[source(Customer)] :
        tableState(Table, occupied(Customer)) <-
    .print("manager: duplicate table request ignored; ", Customer,
           " is already seated at ", Table).

+!requestTable(Customer)[source(Customer)] :
        waitingForTable(Customer) <-
    .print("manager: ", Customer, " is already waiting for a clean table.").

// Complete a previously claimed seat.  If the Java model rejects the action
// (for example because another concurrent intention already reserved this
// customer), the failure handler rolls the BDI reservation back safely.
+!completeSeating(Customer, Table) :
        seatingInProgress(Customer, Table) & tableState(Table, reserved(Customer)) <-
    seatCustomer(Customer, Table);
    -seatingInProgress(Customer, Table);
    -tableState(Table, reserved(Customer));
    +tableState(Table, occupied(Customer));
    !clearWaitingFlag(Customer);
    .print("manager: seating committed for ", Customer, " at ", Table);
    .send(Customer, tell, tableAssigned(Customer, Table)).

-!completeSeating(Customer, Table) :
        seatingInProgress(Customer, Table) & tableState(Table, reserved(Customer)) <-
    -seatingInProgress(Customer, Table);
    -tableState(Table, reserved(Customer));
    +tableState(Table, free);
    +waitingForTable(Customer);
    .print("manager: seating race/failure rolled back for ", Customer, " at ", Table).

+!clearWaitingFlag(Customer) : waitingForTable(Customer) <-
    -waitingForTable(Customer).
+!clearWaitingFlag(Customer) : true <- true.

+!freeTable(Customer)[source(cashier)] : tableState(table1, occupied(Customer)) <-
    !cancelCustomerSupplies(Customer, table1);
    // Logical vacancy happens immediately after payment.  The walk to the exit
    // is animation only and must not block cleaning or the waiting-table queue.
    vacateTable(Customer, table1);
    -tableState(table1, occupied(Customer));
    +tableState(table1, dirty);
    .print("manager: table1 is DIRTY; requesting cleaning while ", Customer, " exits");
    !queueCleaning(table1);
    customerLeave(Customer).

+!freeTable(Customer)[source(cashier)] : tableState(table2, occupied(Customer)) <-
    !cancelCustomerSupplies(Customer, table2);
    vacateTable(Customer, table2);
    -tableState(table2, occupied(Customer));
    +tableState(table2, dirty);
    .print("manager: table2 is DIRTY; requesting cleaning while ", Customer, " exits");
    !queueCleaning(table2);
    customerLeave(Customer).

+!freeTable(Customer)[source(cashier)] :
        not tableState(table1, occupied(Customer)) &
        not tableState(table2, occupied(Customer)) <-
    .print("manager: no occupied table found for ", Customer).

// CLEANING QUEUE
+!queueCleaning(Table) :
        tableState(Table, dirty) &
        not pendingCleaning(Table) &
        not activeCleaning(AnyWaiter, Table) <-
    +pendingCleaning(Table);
    !dispatchCleaning.

+!queueCleaning(Table) : pendingCleaning(Table) <- true.
+!queueCleaning(Table) : activeCleaning(Waiter, Table) <- true.

// Cleaning has priority over optional supply tasks when a waiter becomes free.
+!dispatchCleaning :
        pendingCleaning(Table) &
        waiterState(waiter1, free) <-
    -pendingCleaning(Table);
    +activeCleaning(waiter1, Table);
    -waiterState(waiter1, free);
    +waiterState(waiter1, busy(cleaning(Table)));
    .print("manager: assigning table cleaning ", Table, " to waiter1");
    .send(waiter1, achieve, cleanTableTask(Table)).

+!dispatchCleaning :
        pendingCleaning(Table) &
        not waiterState(waiter1, free) &
        waiterState(waiter2, free) <-
    -pendingCleaning(Table);
    +activeCleaning(waiter2, Table);
    -waiterState(waiter2, free);
    +waiterState(waiter2, busy(cleaning(Table)));
    .print("manager: assigning table cleaning ", Table, " to waiter2");
    .send(waiter2, achieve, cleanTableTask(Table)).

+!dispatchCleaning : true <- true.

+!tableCleaned(Waiter, Table)[source(Waiter)] :
        activeCleaning(Waiter, Table) & tableState(Table, dirty) <-
    -activeCleaning(Waiter, Table);
    -tableState(Table, dirty);
    +tableState(Table, free);
    .print("manager: ", Table, " cleaned by ", Waiter, " and is ready");
    !seatWaitingCustomer(Table);
    !dispatchCleaning;
    !dispatchSupplies.

+!tableCleaned(Waiter, Table)[source(Waiter)] :
        not activeCleaning(Waiter, Table) <- true.

+!cleaningDeferred(Waiter, Table)[source(Waiter)] :
        activeCleaning(Waiter, Table) & waiterState(Waiter, busy(cleaning(Table))) <-
    -activeCleaning(Waiter, Table);
    +pendingCleaning(Table);
    -waiterState(Waiter, busy(cleaning(Table)));
    +waiterState(Waiter, busy(reported_busy));
    .print("manager: cleaning ", Table, " deferred; ", Waiter, " reported another active task");
    !dispatchCleaning.

// A waiting customer is seated as soon as the cleaned table becomes free.
+!seatWaitingCustomer(Table) :
        tableState(Table, free) & waitingForTable(Customer) &
        not seatingInProgress(Customer, AnyTable) <-
    // Claim the cleaned table before invoking the slow physical movement.
    -tableState(Table, free);
    +tableState(Table, reserved(Customer));
    +seatingInProgress(Customer, Table);
    -waitingForTable(Customer);
    .print("manager: claimed cleaned ", Table, " for queued ", Customer);
    !completeSeating(Customer, Table).

// If another cleaned table already claimed the same queued customer, this
// intention simply leaves the current table free for the next guest.
+!seatWaitingCustomer(Table) : true <- true.

// ADAPTIVE WAITER CONTRACT NET

+!newOrder(Customer, Table, Dish, Priority)[source(Customer)] :
        tableState(Table, occupied(Customer)) <-
    .concat("order_", Customer, "_", Dish, OrderId);
    .print("manager: CFP for ", Dish, " priority=", Priority, " (", OrderId, ")");
    setTableOrder(Table, Dish, ordered);
    +activeOrder(OrderId, Customer, Table, Dish, Priority);
    +orderAssignment(OrderId, Customer, none);
    !requestWaiterBids(OrderId, Customer, Table, Dish, Priority).

+!requestWaiterBids(OrderId, Customer, Table, Dish, Priority) :
        activeOrder(OrderId, Customer, Table, Dish, Priority) &
        waiterReputation(waiter1, R1) & waiterReputation(waiter2, R2) <-
    .send(waiter1, achieve, cfp(OrderId, Customer, Table, Dish, Priority, R1));
    .send(waiter2, achieve, cfp(OrderId, Customer, Table, Dish, Priority, R2));
    .wait(900);
    !chooseWaiter(OrderId).

+!propose(OrderId, Waiter, Score)[source(Waiter)] :
        activeOrder(OrderId, Customer, Table, Dish, Priority) &
        waiterBid(OrderId, Waiter, OldScore) <-
    -waiterBid(OrderId, Waiter, OldScore);
    +waiterBid(OrderId, Waiter, Score);
    .print("manager: adaptive bid ", Score, " from ", Waiter, " for ", OrderId).

+!propose(OrderId, Waiter, Score)[source(Waiter)] :
        activeOrder(OrderId, Customer, Table, Dish, Priority) &
        not waiterBid(OrderId, Waiter, OldScore) <-
    +waiterBid(OrderId, Waiter, Score);
    .print("manager: adaptive bid ", Score, " from ", Waiter, " for ", OrderId).

+!propose(OrderId, Waiter, Score)[source(Waiter)] :
        not activeOrder(OrderId, Customer, Table, Dish, Priority) <-
    .print("manager: ignoring late bid from ", Waiter, " for ", OrderId).

+!chooseWaiter(OrderId) :
        activeOrder(OrderId, Customer, Table, Dish, Priority) &
        waiterBid(OrderId, waiter1, B1) & waiterBid(OrderId, waiter2, B2) &
        B1 < 999 & B1 <= B2 & waiterState(waiter1, free) <-
    !assignWaiter(OrderId, Customer, Table, Dish, Priority, waiter1, waiter2, B1).

+!chooseWaiter(OrderId) :
        activeOrder(OrderId, Customer, Table, Dish, Priority) &
        waiterBid(OrderId, waiter1, B1) & waiterBid(OrderId, waiter2, B2) &
        B2 < 999 & B2 < B1 & waiterState(waiter2, free) <-
    !assignWaiter(OrderId, Customer, Table, Dish, Priority, waiter2, waiter1, B2).

+!chooseWaiter(OrderId) : activeOrder(OrderId, Customer, Table, Dish, Priority) <-
    .print("manager: no acceptable waiter bid for ", OrderId, "; retrying");
    .wait(1100);
    !requestWaiterBids(OrderId, Customer, Table, Dish, Priority).

+!assignWaiter(OrderId, Customer, Table, Dish, Priority, Selected, Other, Score) :
        waiterState(Selected, free) <-
    .print("manager: selecting ", Selected, " with score ", Score, " for ", OrderId);
    -waiterState(Selected, free);
    +waiterState(Selected, busy(OrderId));
    -activeOrder(OrderId, Customer, Table, Dish, Priority);
    -orderAssignment(OrderId, Customer, none);
    +orderAssignment(OrderId, Customer, Selected);
    +assignedOrderDetail(OrderId, Customer, Table, Dish, Priority, Selected);
    .send(Selected, achieve, acceptOrder(OrderId, Customer, Table, Dish, Priority));
    .send(Other, achieve, rejectOrder(OrderId)).

// Confirmation is sent only after the selected waiter confirms that its own
// local availability state allowed the task to be accepted.
+!orderAccepted(OrderId, Waiter)[source(Waiter)] :
        orderAssignment(OrderId, Customer, Waiter) &
        assignedOrderDetail(OrderId, Customer, Table, Dish, Priority, Waiter) &
        waiterState(Waiter, busy(OrderId)) <-
    .send(Customer, tell, orderConfirmed(Customer, Dish, Waiter, Priority)).

// Defensive recovery if local waiter state and Manager state were briefly out of sync.
+!orderDeclined(OrderId, Waiter)[source(Waiter)] :
        orderAssignment(OrderId, Customer, Waiter) &
        assignedOrderDetail(OrderId, Customer, Table, Dish, Priority, Waiter) &
        waiterState(Waiter, busy(OrderId)) <-
    .print("manager: ", Waiter, " declined ", OrderId, " because it is still busy; reopening CFP");
    -orderAssignment(OrderId, Customer, Waiter);
    -assignedOrderDetail(OrderId, Customer, Table, Dish, Priority, Waiter);
    +orderAssignment(OrderId, Customer, none);
    -waiterState(Waiter, busy(OrderId));
    +waiterState(Waiter, busy(reported_busy));
    +activeOrder(OrderId, Customer, Table, Dish, Priority);
    .wait(300);
    !requestWaiterBids(OrderId, Customer, Table, Dish, Priority).

// EVENT-DRIVEN SUPPLY QUEUE
// Requests are queued once. A waiterFree event dispatches the next request;
// there is no polling loop. Pending/active requests are cancelled when the
// customer leaves, and waiters receive an explicit cancellation message.

+!supplyRequest(Customer, Table, Item)[source(Customer)] :
        tableState(Table, occupied(Customer)) &
        not pendingSupply(Customer, Table, Item) &
        not activeSupply(AnyWaiter, Customer, Table, Item) <-
    +pendingSupply(Customer, Table, Item);
    .print("manager: queued supply ", Item, " for ", Customer, " at ", Table);
    !dispatchSupplies.

+!supplyRequest(Customer, Table, Item)[source(Customer)] :
        pendingSupply(Customer, Table, Item) <-
    .print("manager: duplicate queued supply request ignored for ", Item).

+!supplyRequest(Customer, Table, Item)[source(Customer)] :
        activeSupply(Waiter, Customer, Table, Item) <-
    .print("manager: supply ", Item, " is already assigned to ", Waiter).

+!supplyRequest(Customer, Table, Item)[source(Customer)] :
        not tableState(Table, occupied(Customer)) <-
    .print("manager: rejected stale supply request for ", Customer, " / ", Item).

+!dispatchSupplies :
        pendingSupply(Customer, Table, Item) & tableState(Table, occupied(Customer)) &
        waiterState(waiter1, free) & waiterState(waiter2, free) &
        waiterReputation(waiter1, R1) & waiterReputation(waiter2, R2) & R1 >= R2 <-
    !assignSupply(waiter1, Customer, Table, Item);
    !dispatchSupplies.

+!dispatchSupplies :
        pendingSupply(Customer, Table, Item) & tableState(Table, occupied(Customer)) &
        waiterState(waiter1, free) & waiterState(waiter2, free) &
        waiterReputation(waiter1, R1) & waiterReputation(waiter2, R2) & R2 > R1 <-
    !assignSupply(waiter2, Customer, Table, Item);
    !dispatchSupplies.

+!dispatchSupplies :
        pendingSupply(Customer, Table, Item) & tableState(Table, occupied(Customer)) &
        waiterState(waiter1, free) & not waiterState(waiter2, free) <-
    !assignSupply(waiter1, Customer, Table, Item);
    !dispatchSupplies.

+!dispatchSupplies :
        pendingSupply(Customer, Table, Item) & tableState(Table, occupied(Customer)) &
        not waiterState(waiter1, free) & waiterState(waiter2, free) <-
    !assignSupply(waiter2, Customer, Table, Item);
    !dispatchSupplies.

// Remove a queued request whose customer is no longer seated.
+!dispatchSupplies :
        pendingSupply(Customer, Table, Item) &
        not tableState(Table, occupied(Customer)) <-
    -pendingSupply(Customer, Table, Item);
    .print("manager: cancelled stale queued supply ", Item, " for ", Customer);
    !dispatchSupplies.

// No pending request can currently be assigned. Wait for a waiterFree event.
+!dispatchSupplies : true <- true.

+!assignSupply(Waiter, Customer, Table, Item) :
        pendingSupply(Customer, Table, Item) &
        tableState(Table, occupied(Customer)) & waiterState(Waiter, free) <-
    -pendingSupply(Customer, Table, Item);
    +activeSupply(Waiter, Customer, Table, Item);
    -waiterState(Waiter, free);
    +waiterState(Waiter, busy(supply(Customer, Item)));
    .print("manager: assigning queued supply ", Item, " to ", Waiter);
    .send(Waiter, achieve, handleSupply(Customer, Table, Item)).

+!supplyCompleted(Waiter, Customer, Table, Item)[source(Waiter)] :
        activeSupply(Waiter, Customer, Table, Item) <-
    -activeSupply(Waiter, Customer, Table, Item);
    .print("manager: supply ", Item, " completed for ", Customer).

+!supplyCompleted(Waiter, Customer, Table, Item)[source(Waiter)] :
        not activeSupply(Waiter, Customer, Table, Item) <-
    .print("manager: ignoring late completion for cancelled supply ", Item).

+!supplyDeferred(Waiter, Customer, Table, Item)[source(Waiter)] :
        activeSupply(Waiter, Customer, Table, Item) &
        waiterState(Waiter, busy(supply(Customer, Item))) &
        tableState(Table, occupied(Customer)) <-
    -activeSupply(Waiter, Customer, Table, Item);
    +pendingSupply(Customer, Table, Item);
    -waiterState(Waiter, busy(supply(Customer, Item)));
    +waiterState(Waiter, busy(reported_busy));
    .print("manager: ", Waiter, " deferred ", Item, "; request returned to queue");
    !dispatchSupplies.

+!supplyDeferred(Waiter, Customer, Table, Item)[source(Waiter)] :
        activeSupply(Waiter, Customer, Table, Item) &
        waiterState(Waiter, busy(supply(Customer, Item))) &
        not tableState(Table, occupied(Customer)) <-
    -activeSupply(Waiter, Customer, Table, Item);
    -waiterState(Waiter, busy(supply(Customer, Item)));
    +waiterState(Waiter, busy(reported_busy)).

+!supplyCancelled(Waiter, Customer, Table, Item)[source(Waiter)] :
        activeSupply(Waiter, Customer, Table, Item) <-
    -activeSupply(Waiter, Customer, Table, Item);
    .print("manager: active supply ", Item, " cancelled by ", Waiter).

+!supplyCancelled(Waiter, Customer, Table, Item)[source(Waiter)] :
        not activeSupply(Waiter, Customer, Table, Item) <- true.

+!cancelCustomerSupplies(Customer, Table) : true <-
    !cancelPendingSupplies(Customer, Table);
    !cancelActiveSupplies(Customer, Table).

+!cancelPendingSupplies(Customer, Table) : pendingSupply(Customer, Table, Item) <-
    -pendingSupply(Customer, Table, Item);
    .print("manager: removed queued ", Item, " because ", Customer, " is leaving");
    !cancelPendingSupplies(Customer, Table).

+!cancelPendingSupplies(Customer, Table) : true <- true.

+!cancelActiveSupplies(Customer, Table) : activeSupply(Waiter, Customer, Table, Item) <-
    -activeSupply(Waiter, Customer, Table, Item);
    .print("manager: cancelling active ", Item, " delivery handled by ", Waiter);
    .send(Waiter, achieve, cancelSupply(Customer, Table, Item));
    !cancelActiveSupplies(Customer, Table).

+!cancelActiveSupplies(Customer, Table) : true <- true.

+!waiterFree(Waiter)[source(Waiter)] :
        waiterState(Waiter, busy(OrderId)) &
        assignedOrderDetail(OrderId, Customer, Table, Dish, Priority, Waiter) <-
    -assignedOrderDetail(OrderId, Customer, Table, Dish, Priority, Waiter);
    -orderAssignment(OrderId, Customer, Waiter);
    -waiterState(Waiter, busy(OrderId));
    +waiterState(Waiter, free);
    .print("manager: ", Waiter, " completed ", OrderId, " and is free again.");
    !dispatchCleaning;
    !dispatchSupplies.

+!waiterFree(Waiter)[source(Waiter)] : waiterState(Waiter, busy(cleaning(Table))) <-
    -waiterState(Waiter, busy(cleaning(Table)));
    +waiterState(Waiter, free);
    .print("manager: ", Waiter, " finished cleaning and is free again.");
    !dispatchCleaning;
    !dispatchSupplies.

+!waiterFree(Waiter)[source(Waiter)] : waiterState(Waiter, busy(supply(Customer, Item))) <-
    -waiterState(Waiter, busy(supply(Customer, Item)));
    +waiterState(Waiter, free);
    .print("manager: ", Waiter, " finished a supply task and is free again.");
    !dispatchCleaning;
    !dispatchSupplies.

+!waiterFree(Waiter)[source(Waiter)] : waiterState(Waiter, busy(reported_busy)) <-
    -waiterState(Waiter, busy(reported_busy));
    +waiterState(Waiter, free);
    .print("manager: ", Waiter, " reports its previous task is now complete.");
    !dispatchCleaning;
    !dispatchSupplies.

+!waiterFree(Waiter)[source(Waiter)] : waiterState(Waiter, free) <-
    !dispatchCleaning;
    !dispatchSupplies.

// REPUTATION AND CUSTOMER FEEDBACK

+!waiterOutcome(Waiter, success)[source(Waiter)] : waiterReputation(Waiter, R) <-
    NewR = R + 2;
    -waiterReputation(Waiter, R);
    +waiterReputation(Waiter, NewR);
    setReputation(Waiter, NewR);
    .print("manager: ", Waiter, " reputation increased to ", NewR).

+!waiterOutcome(Waiter, failure)[source(Waiter)] : waiterReputation(Waiter, R) <-
    NewR = R - 5;
    -waiterReputation(Waiter, R);
    +waiterReputation(Waiter, NewR);
    setReputation(Waiter, NewR);
    .print("manager: ", Waiter, " reputation decreased to ", NewR).

+!customerFeedback(Customer, Waiter, Score)[source(Customer)] :
        waiterReputation(Waiter, R) & Score >= 82 <-
    NewR = R + 1;
    -waiterReputation(Waiter, R);
    +waiterReputation(Waiter, NewR);
    setReputation(Waiter, NewR);
    .print("manager: positive feedback from ", Customer, " for ", Waiter, " (", Score, ")").

+!customerFeedback(Customer, Waiter, Score)[source(Customer)] :
        waiterReputation(Waiter, R) & Score < 82 & Score >= 60 <-
    .print("manager: neutral feedback from ", Customer, " for ", Waiter, " (", Score, ")").

+!customerFeedback(Customer, Waiter, Score)[source(Customer)] :
        waiterReputation(Waiter, R) & Score < 60 <-
    NewR = R - 3;
    -waiterReputation(Waiter, R);
    +waiterReputation(Waiter, NewR);
    setReputation(Waiter, NewR);
    .print("manager: negative feedback from ", Customer, " for ", Waiter, " (", Score, ")").
