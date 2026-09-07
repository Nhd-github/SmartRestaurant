// cashier.asl
// Calculates bills and explicitly reports payment failures to customers.

price(pasta, 12).
price(pizza, 14).
price(salad, 9).
price(risotto, 13).
price(soup, 10).

+!requestBill(Customer, Dish)[source(Customer)] : price(Dish, Amount) <-
    !goTo(cashier, paymentDesk);
    +pendingPayment(Customer, Amount);
    .print("cashier: bill for ", Customer, " = ", Amount, " (", Dish, ")");
    .send(Customer, achieve, payBill(Amount)).

+!requestBill(Customer, Dish)[source(Customer)] : not price(Dish, Amount) <-
    +pendingPayment(Customer, 0);
    .send(Customer, achieve, payBill(0)).

+paymentReceived(Customer, Amount) : pendingPayment(Customer, Amount) <-
    clearPaymentReceived(Customer, Amount);
    -pendingPayment(Customer, Amount);
    .send(Customer, tell, paymentComplete(Customer));
    .send(restaurantManager, achieve, freeTable(Customer));
    !goTo(cashier, start).

+paymentFailed(Customer, Amount) : pendingPayment(Customer, Amount) <-
    clearPaymentFailed(Customer, Amount);
    .send(Customer, tell, paymentRetry(Customer, Amount));
    .wait(1200);
    .send(Customer, achieve, payBill(Amount)).

+paymentReceived(Customer, Amount) : not pendingPayment(Customer, Amount) <-
    clearPaymentReceived(Customer, Amount).

+paymentFailed(Customer, Amount) : not pendingPayment(Customer, Amount) <-
    clearPaymentFailed(Customer, Amount).

+!goTo(cashier, Loc) : true <- move(cashier, Loc).
