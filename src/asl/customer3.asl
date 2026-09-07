// customer3.asl
// Arrives later, after earlier meals have consumed stock. This customer is
// used to demonstrate out-of-stock detection and an inventory alternative.

trusted(cashier).
myTable(none).
satisfaction(70).
orderPriority(5).
assignedWaiter(none).
servedDish(none).
supplyState(pending).

!arrive(customer3).

+!arrive(Customer) : true <-
    .wait(6500);
    customerArrive(Customer);
    .print(Customer, " arrived after the first two customers.");
    .send(restaurantManager, achieve, requestTable(Customer)).

+tableAssigned(Customer, Table)[source(restaurantManager)] : myTable(none) <-
    -myTable(none);
    +myTable(Table);
    .print(Customer, ": seated at ", Table);
    !placeOrder(Customer, pizza).

// Defensive guard: a customer already seated at one table ignores any stale
// duplicate assignment message instead of creating a second order.
+tableAssigned(Customer, Table)[source(restaurantManager)] :
        myTable(Current) & Current \== none <-
    .print(Customer, ": ignoring duplicate table assignment to ", Table,
           "; already seated at ", Current).

+tableQueued(Customer)[source(restaurantManager)] : true <-
    .print(Customer, ": no clean table is free; waiting in the entrance queue.").

+!placeOrder(Customer, Dish) : myTable(Table) & Table \== none & orderPriority(P) <-
    .print(Customer, " ordering ", Dish, " priority=", P);
    .send(restaurantManager, achieve, newOrder(Customer, Table, Dish, P));
    !requestSupply(Customer, cutlery).

+orderConfirmed(Customer, Dish, Waiter, Priority)[source(restaurantManager)] : true <-
    -assignedWaiter(none);
    +assignedWaiter(Waiter);
    .print(Customer, ": ", Dish, " confirmed with ", Waiter, " priority=", Priority).

// Alternatives are proposals, not unilateral waiter decisions.
// The customer evaluates the proposal and explicitly replies to the waiter.
+!considerAlternative(OrderId, OriginalDish, AltDish, Reason, Details, Waiter)[source(Waiter)] :
        satisfaction(S) & S >= 40 & AltDish \== none <-
    -satisfaction(S);
    +satisfaction(S - 8);
    .print("customer3: ", OriginalDish, " cannot continue (", Details,
           "). I explicitly ACCEPT ", AltDish, " [", Reason, "]");
    .send(Waiter, achieve,
          alternativeAccepted(OrderId, OriginalDish, AltDish, Reason)).

+!considerAlternative(OrderId, OriginalDish, AltDish, Reason, Details, Waiter)[source(Waiter)] :
        satisfaction(S) & S < 40 <-
    .print("customer3: rejecting alternative ", AltDish, " because satisfaction is too low");
    .send(Waiter, achieve,
          alternativeRejected(OrderId, OriginalDish, AltDish, Reason)).

+orderUnavailable(Customer, Dish, Missing)[source(Waiter)] : satisfaction(S) <-
    -satisfaction(S);
    +satisfaction(S - 12);
    .print(Customer, ": order cancelled; ", Dish, " unavailable and no alternative exists. Missing=", Missing).

+coordinationDelay(Customer, Reason)[source(Waiter)] : satisfaction(S) <-
    -satisfaction(S);
    +satisfaction(S - 5);
    .print(Customer, ": coordination delay (", Reason, ").").

+orderServed(Customer, Dish)[source(Waiter)] : satisfaction(S) & servedDish(OldDish) <-
    -satisfaction(S);
    +satisfaction(S + 12);
    -servedDish(OldDish);
    +servedDish(Dish);
    .print(Customer, ": ", Dish, " served by ", Waiter, ".");
    !maybeRequestBill(Customer).

+!requestSupply(Customer, Item) : myTable(Table) & Table \== none <-
    .wait(3500);
    .send(restaurantManager, achieve, supplyRequest(Customer, Table, Item)).

+supplyDelivered(Customer, Item)[source(Waiter)] : satisfaction(S) & supplyState(OldState) <-
    -satisfaction(S);
    +satisfaction(S + 4);
    -supplyState(OldState);
    +supplyState(delivered);
    .print(Customer, ": ", Item, " delivered by ", Waiter, ".");
    !maybeRequestBill(Customer).

+supplyUnavailable(Customer, Item)[source(Waiter)] : satisfaction(S) & supplyState(OldState) <-
    -satisfaction(S);
    +satisfaction(S - 6);
    -supplyState(OldState);
    +supplyState(unavailable);
    .print(Customer, ": ", Item, " is unavailable; continuing without it.");
    !maybeRequestBill(Customer).

+!maybeRequestBill(Customer) :
        servedDish(Dish) & Dish \== none &
        supplyState(delivered) & not billRequested <-
    +billRequested;
    .print(Customer, ": meal and cutlery are ready; using the table items.");
    .wait(3500);
    !requestBill(Customer, Dish).

+!maybeRequestBill(Customer) :
        servedDish(Dish) & Dish \== none &
        supplyState(unavailable) & not billRequested <-
    +billRequested;
    .print(Customer, ": meal is ready; requesting bill without cutlery.");
    .wait(2000);
    !requestBill(Customer, Dish).

+!maybeRequestBill(Customer) : true <- true.

+!requestBill(Customer, Dish) : true <-
    .send(cashier, achieve, requestBill(Customer, Dish)).

+!payBill(Amount)[source(S)] : trusted(S) <-
    .print("customer3 paying ", Amount);
    payAmount(Amount).

+paymentRetry(Customer, Amount)[source(cashier)] : satisfaction(S) <-
    -satisfaction(S);
    +satisfaction(S - 4);
    .print(Customer, ": payment failed; retrying ", Amount).

+paymentComplete(Customer)[source(cashier)] :
        myTable(Table) & satisfaction(S) & assignedWaiter(Waiter) <-
    !publishExperience(Customer, Waiter, S);
    .print(Customer, ": payment complete; leaving ", Table);
    -myTable(Table);
    +myTable(none).

+!publishExperience(Customer, Waiter, Score) : Score >= 82 <-
    setSatisfaction(Customer, Score, satisfied);
    .send(restaurantManager, achieve, customerFeedback(Customer, Waiter, Score)).

+!publishExperience(Customer, Waiter, Score) : Score < 82 & Score >= 60 <-
    setSatisfaction(Customer, Score, neutral);
    .send(restaurantManager, achieve, customerFeedback(Customer, Waiter, Score)).

+!publishExperience(Customer, Waiter, Score) : Score < 60 <-
    setSatisfaction(Customer, Score, disappointed);
    .send(restaurantManager, achieve, customerFeedback(Customer, Waiter, Score)).
