import jason.asSyntax.Literal;
import jason.asSyntax.NumberTerm;
import jason.asSyntax.Structure;
import jason.environment.Environment;
import jason.environment.grid.Location;

import java.util.logging.Logger;

/** Environment for the Smart Restaurant MAS. */
public class RestaurantEnv extends Environment {

    private static final Logger logger = Logger.getLogger(RestaurantEnv.class.getName());
    private RestaurantModel model;

    @Override
    public void init(String[] args) {
        model = new RestaurantModel();
        if (args.length == 1 && "on".equals(args[0])) {
            RestaurantView view = new RestaurantView(model);
            model.setView(view);
        }
    }

    @Override
    public boolean executeAction(String ag, Structure action) {
        System.out.println("[" + ag + "] doing: " + action);

        boolean result;
        String functor = action.getFunctor();

        try {
            if ("move".equals(functor)) {
                result = executeMove(action);
            } else if ("customerArrive".equals(functor)) {
                result = model.customerArrive(action.getTerm(0).toString());
            } else if ("seatCustomer".equals(functor)) {
                result = model.seatCustomer(
                        action.getTerm(0).toString(),
                        action.getTerm(1).toString());
            } else if ("vacateTable".equals(functor)) {
                result = model.vacateTable(
                        action.getTerm(0).toString(),
                        action.getTerm(1).toString());
            } else if ("customerLeave".equals(functor)) {
                result = model.customerLeave(action.getTerm(0).toString());
            } else if ("reclaimReusableSupply".equals(functor)) {
                result = executeReclaimReusableSupply(action);
            } else if ("clearReusableSupplyRecovered".equals(functor)) {
                result = clearReusableSupplyRecovered(action);
            } else if ("cleanTable".equals(functor)) {
                result = model.cleanTable(
                        action.getTerm(0).toString(),
                        action.getTerm(1).toString());
            } else if ("reserveRecipe".equals(functor)) {
                result = executeReserveRecipe(ag, action);
            } else if ("releaseRecipe".equals(functor)) {
                result = model.releaseRecipe(
                        action.getTerm(0).toString(),
                        action.getTerm(1).toString());
            } else if ("startCooking".equals(functor)) {
                result = model.startCooking(
                        action.getTerm(0).toString(),
                        action.getTerm(1).toString());
            } else if ("clearReservationSuccess".equals(functor)) {
                result = clearReservationSuccess(action);
            } else if ("clearReservationFail".equals(functor)) {
                result = clearReservationFail(action);
            } else if ("clearReservationPreempted".equals(functor)) {
                result = clearReservationPreempted(action);
            } else if ("setReputation".equals(functor)) {
                result = executeSetReputation(action);
            } else if ("setSatisfaction".equals(functor)) {
                result = executeSetSatisfaction(action);
            } else if ("recordReplan".equals(functor)) {
                result = executeRecordReplan(action);
            } else if ("lockIngredient".equals(functor)) {
                result = executeLock(ag, action);
            } else if ("unlockIngredient".equals(functor)) {
                result = executeUnlock(ag, action);
            } else if ("prepareDish".equals(functor)) {
                if (action.getArity() >= 3) {
                    result = model.prepareDish(
                            action.getTerm(0).toString(),
                            action.getTerm(1).toString(),
                            action.getTerm(2).toString());
                } else {
                    result = model.prepareDish(action.getTerm(0).toString());
                }
            } else if ("pickupDish".equals(functor)) {
                result = model.pickupDish(action.getTerm(0).toString());
            } else if ("serveDish".equals(functor)) {
                String dish = action.getTerm(0).toString();
                String table = action.getArity() > 1
                        ? action.getTerm(1).toString() : null;
                result = model.serveDish(dish, table);
            } else if ("setTableOrder".equals(functor)) {
                result = model.setTableOrder(
                        action.getTerm(0).toString(),
                        action.getTerm(1).toString(),
                        action.getTerm(2).toString());
            } else if ("checkDishStock".equals(functor)) {
                result = executeCheckDishStock(action);
            } else if ("clearDishStockAvailable".equals(functor)) {
                result = clearDishStockAvailable(action);
            } else if ("clearDishStockUnavailable".equals(functor)) {
                result = clearDishStockUnavailable(action);
            } else if ("findAlternativeStock".equals(functor)) {
                result = executeFindAlternativeStock(action);
            } else if ("clearAlternativeStockResult".equals(functor)) {
                result = clearAlternativeStockResult(action);
            } else if ("takeSupply".equals(functor)) {
                result = model.takeSupply(action.getTerm(0).toString());
            } else if ("deliverSupply".equals(functor)) {
                if (action.getArity() >= 3) {
                    result = model.deliverSupply(
                            action.getTerm(0).toString(),
                            action.getTerm(1).toString(),
                            action.getTerm(2).toString());
                } else {
                    result = model.deliverSupply(action.getTerm(0).toString());
                }
            } else if ("returnSupply".equals(functor)) {
                result = model.returnSupply(action.getTerm(0).toString());
            } else if ("payAmount".equals(functor)) {
                result = executePayment(ag, action);
            } else if ("clearPaymentReceived".equals(functor)) {
                result = clearPaymentPercept(action, true);
            } else if ("clearPaymentFailed".equals(functor)) {
                result = clearPaymentPercept(action, false);
            } else {
                logger.warning("Unknown action: " + action + " from " + ag);
                result = false;
            }
        } catch (Exception e) {
            logger.severe("Action failed: " + action + " from " + ag + " -> " + e);
            result = false;
        }

        if (result) {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return result;
    }

    private boolean executeMove(Structure action) {
        String agent = action.getTerm(0).toString();
        String location = action.getTerm(1).toString();
        Location destination = model.resolveDestination(agent, location);

        if (destination == null) {
            logger.warning("Unknown destination " + location + " for " + agent);
            return false;
        }
        return model.moveAgent(agent, destination);
    }

    private boolean executeReserveRecipe(String ag, Structure action) throws Exception {
        String dish = action.getTerm(0).toString();
        String chef = action.getTerm(1).toString();
        String orderId = action.getTerm(2).toString();
        int priority = (int) ((NumberTerm) action.getTerm(3)).solve();

        RestaurantModel.ReservationResult reservation =
                model.reserveRecipe(dish, chef, orderId, priority);

        if (reservation.success) {
            Literal success = Literal.parseLiteral(
                    "recipeReservationSuccess(" + orderId + "," + dish + "," + chef + ")");
            removePercept(ag, success);
            addPercept(ag, success);

            for (RestaurantModel.PreemptionNotice notice : reservation.preemptions) {
                Literal preempted = Literal.parseLiteral(
                        "reservationPreempted(" + notice.victimOrder + ","
                                + notice.victimDish + "," + notice.victimChef + ","
                                + notice.byOrder + "," + notice.byChef + ")");
                removePercept(notice.victimChef, preempted);
                addPercept(notice.victimChef, preempted);
            }
        } else {
            Literal failure = Literal.parseLiteral(
                    "recipeReservationFail(" + orderId + "," + dish + "," + chef
                            + "," + reservation.reason + ")");
            removePercept(ag, failure);
            addPercept(ag, failure);
        }
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean clearReservationSuccess(Structure action) {
        String orderId = action.getTerm(0).toString();
        String dish = action.getTerm(1).toString();
        String chef = action.getTerm(2).toString();
        removePercept(chef, Literal.parseLiteral(
                "recipeReservationSuccess(" + orderId + "," + dish + "," + chef + ")"));
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean clearReservationFail(Structure action) {
        String orderId = action.getTerm(0).toString();
        String dish = action.getTerm(1).toString();
        String chef = action.getTerm(2).toString();
        String reason = action.getTerm(3).toString();
        removePercept(chef, Literal.parseLiteral(
                "recipeReservationFail(" + orderId + "," + dish + "," + chef
                        + "," + reason + ")"));
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean clearReservationPreempted(Structure action) {
        String victimOrder = action.getTerm(0).toString();
        String victimDish = action.getTerm(1).toString();
        String victimChef = action.getTerm(2).toString();
        String byOrder = action.getTerm(3).toString();
        String byChef = action.getTerm(4).toString();
        removePercept(victimChef, Literal.parseLiteral(
                "reservationPreempted(" + victimOrder + "," + victimDish + ","
                        + victimChef + "," + byOrder + "," + byChef + ")"));
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean executeSetReputation(Structure action) throws Exception {
        String agent = action.getTerm(0).toString();
        int value = (int) ((NumberTerm) action.getTerm(1)).solve();
        model.setReputation(agent, value);
        return true;
    }

    private boolean executeSetSatisfaction(Structure action) throws Exception {
        String customer = action.getTerm(0).toString();
        int score = (int) ((NumberTerm) action.getTerm(1)).solve();
        String mood = action.getTerm(2).toString();
        model.setSatisfaction(customer, score, mood);
        return true;
    }

    private boolean executeRecordReplan(Structure action) {
        model.recordReplan(action.getTerm(0).toString(),
                action.getTerm(1).toString(), action.getTerm(2).toString());
        return true;
    }

    // Compatibility actions retained for older plans.
    private boolean executeLock(String ag, Structure action) {
        String dish = action.getTerm(0).toString();
        String chef = action.getTerm(1).toString();
        String legacyOrder = "legacy_" + chef + "_" + dish;
        RestaurantModel.ReservationResult result =
                model.reserveRecipe(dish, chef, legacyOrder, 1);
        return result.success;
    }

    private boolean executeUnlock(String ag, Structure action) {
        String dish = action.getTerm(0).toString();
        String chef = action.getTerm(1).toString();
        return model.releaseRecipe("legacy_" + chef + "_" + dish, chef);
    }

    private boolean executeCheckDishStock(Structure action) throws Exception {
        String dish = action.getTerm(0).toString();
        String waiter = action.getTerm(1).toString();
        String orderId = action.getTerm(2).toString();

        java.util.List<String> missing = model.getMissingIngredients(dish);
        if (missing.isEmpty()) {
            Literal available = Literal.parseLiteral(
                    "dishStockAvailable(" + dish + "," + orderId + "," + waiter + ")");
            removePercept("inventoryManager", available);
            addPercept("inventoryManager", available);
        } else {
            String alt = model.findAvailableAlternative(dish);
            if (alt == null) alt = "none";
            String missingList = "[" + String.join(",", missing) + "]";
            Literal unavailable = Literal.parseLiteral(
                    "dishStockUnavailable(" + dish + "," + missingList + "," + alt
                            + "," + orderId + "," + waiter + ")");
            removePercept("inventoryManager", unavailable);
            addPercept("inventoryManager", unavailable);
        }
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean clearDishStockAvailable(Structure action) throws Exception {
        String dish = action.getTerm(0).toString();
        String orderId = action.getTerm(1).toString();
        String waiter = action.getTerm(2).toString();
        removePercept("inventoryManager", Literal.parseLiteral(
                "dishStockAvailable(" + dish + "," + orderId + "," + waiter + ")"));
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean clearDishStockUnavailable(Structure action) throws Exception {
        String dish = action.getTerm(0).toString();
        String missing = action.getTerm(1).toString();
        String alt = action.getTerm(2).toString();
        String orderId = action.getTerm(3).toString();
        String waiter = action.getTerm(4).toString();
        removePercept("inventoryManager", Literal.parseLiteral(
                "dishStockUnavailable(" + dish + "," + missing + "," + alt + ","
                        + orderId + "," + waiter + ")"));
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean executeFindAlternativeStock(Structure action) throws Exception {
        String dish = action.getTerm(0).toString();
        String chef = action.getTerm(1).toString();
        String orderId = action.getTerm(2).toString();
        String customer = action.getTerm(3).toString();
        String priority = action.getTerm(4).toString();
        String waiter = action.getTerm(5).toString();
        String alt = model.findAvailableAlternative(dish);
        if (alt == null) alt = "none";

        Literal result = Literal.parseLiteral(
                "alternativeStockResult(" + dish + "," + alt + "," + chef + ","
                        + orderId + "," + customer + "," + priority + "," + waiter + ")");
        removePercept("inventoryManager", result);
        addPercept("inventoryManager", result);
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean clearAlternativeStockResult(Structure action) throws Exception {
        String dish = action.getTerm(0).toString();
        String alt = action.getTerm(1).toString();
        String chef = action.getTerm(2).toString();
        String orderId = action.getTerm(3).toString();
        String customer = action.getTerm(4).toString();
        String priority = action.getTerm(5).toString();
        String waiter = action.getTerm(6).toString();
        removePercept("inventoryManager", Literal.parseLiteral(
                "alternativeStockResult(" + dish + "," + alt + "," + chef + ","
                        + orderId + "," + customer + "," + priority + "," + waiter + ")"));
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean executeReclaimReusableSupply(Structure action) throws Exception {
        String table = action.getTerm(0).toString();
        String item = action.getTerm(1).toString();
        boolean recovered = model.reclaimReusableSupply(table, item);
        if (recovered) {
            Literal percept = Literal.parseLiteral("reusableSupplyRecovered(" + item + ")");
            removePercept("inventoryManager", percept);
            addPercept("inventoryManager", percept);
            informAgsEnvironmentChanged();
        }
        // Not having the reusable item on this table is a normal no-op, not a failure.
        return true;
    }

    private boolean clearReusableSupplyRecovered(Structure action) throws Exception {
        String item = action.getTerm(0).toString();
        removePercept("inventoryManager", Literal.parseLiteral(
                "reusableSupplyRecovered(" + item + ")"));
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean executePayment(String customer, Structure action) throws Exception {
        int amount = (int) ((NumberTerm) action.getTerm(0)).solve();
        model.moveCustomerToPayment(customer);
        boolean paid = model.receivePayment(customer, amount);

        Literal received = Literal.parseLiteral(
                "paymentReceived(" + customer + "," + amount + ")");
        Literal failed = Literal.parseLiteral(
                "paymentFailed(" + customer + "," + amount + ")");

        removePercept("cashier", received);
        removePercept("cashier", failed);
        addPercept("cashier", paid ? received : failed);
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean clearPaymentPercept(Structure action, boolean received) throws Exception {
        String customer = action.getTerm(0).toString();
        int amount = (int) ((NumberTerm) action.getTerm(1)).solve();
        String name = received ? "paymentReceived" : "paymentFailed";
        removePercept("cashier", Literal.parseLiteral(
                name + "(" + customer + "," + amount + ")"));
        informAgsEnvironmentChanged();
        return true;
    }
}
