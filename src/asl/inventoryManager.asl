// inventoryManager.asl
// Checks physical ingredient stock through the environment and proposes
// alternatives. Table supplies remain simple reusable stock items.

alternative(pasta, risotto).
alternative(pizza, soup).
alternative(risotto, salad).
alternative(salad, soup).
alternative(soup, salad).

supply(water, available).
supply(napkins, available).
supply(cutlery, available).

// Food stock is stored in RestaurantModel.java because it is part of the
// physical environment. The inventory agent asks the environment for the
// current state instead of keeping a second copy of the quantities.
+!checkDish(Dish, Waiter, OrderId)[source(Waiter)] : true <-
    checkDishStock(Dish, Waiter, OrderId).

+dishStockAvailable(Dish, OrderId, Waiter) : true <-
    clearDishStockAvailable(Dish, OrderId, Waiter);
    .print("inventoryManager: ", Dish, " is available in current stock");
    .send(Waiter, achieve, dishAvailable(Dish, OrderId)).

+dishStockUnavailable(Dish, Missing, AltDish, OrderId, Waiter) : true <-
    clearDishStockUnavailable(Dish, Missing, AltDish, OrderId, Waiter);
    .print("inventoryManager: ", Dish, " cannot be prepared. Missing=", Missing,
           " alternative=", AltDish);
    .send(Waiter, achieve,
          dishUnavailable(Dish, Missing, AltDish, OrderId)).

// A chef can also ask for an alternative after a booking/preemption failure.
// The same physical stock is checked before the alternative is returned.
+!findAlternative(Dish, Chef, OrderId, Customer, Priority, Waiter)[source(Chef)] : true <-
    findAlternativeStock(Dish, Chef, OrderId, Customer, Priority, Waiter).

+alternativeStockResult(Dish, AltDish, Chef, OrderId, Customer, Priority, Waiter) : true <-
    clearAlternativeStockResult(Dish, AltDish, Chef, OrderId, Customer, Priority, Waiter);
    .print("inventoryManager: alternative for ", Dish, " is ", AltDish);
    .send(Chef, achieve,
          cookAlternative(OrderId, Customer, AltDish, Priority, Waiter)).

// Table supplies are independent from food ingredients.
+!checkSupply(Item, Waiter, Customer, Table)[source(Waiter)] : supply(Item, available) <-
    .print("inventoryManager: ", Item, " is available");
    -supply(Item, available);
    +supply(Item, depleted);
    .send(Waiter, achieve, supplyOk(Item, Customer, Table)).

+!checkSupply(Item, Waiter, Customer, Table)[source(Waiter)] : supply(Item, depleted) <-
    .print("inventoryManager: ", Item, " is out of stock");
    .send(Waiter, achieve, supplyUnavailable(Item, Customer, Table)).

+!restockSupply(Item) : supply(Item, depleted) <-
    -supply(Item, depleted);
    +supply(Item, available);
    .print("inventoryManager: ", Item, " restocked.").

+!restockSupply(Item) : supply(Item, available) <-
    .print("inventoryManager: ", Item, " already available.").

// Reusable cutlery comes back to storage when a waiter cleans the table.
+reusableSupplyRecovered(Item) : supply(Item, depleted) <-
    clearReusableSupplyRecovered(Item);
    -supply(Item, depleted);
    +supply(Item, available);
    .print("inventoryManager: reusable ", Item, " returned after table cleaning.").

+reusableSupplyRecovered(Item) : supply(Item, available) <-
    clearReusableSupplyRecovered(Item).
