import jason.environment.grid.GridWorldModel;
import jason.environment.grid.Location;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.PriorityQueue;
import java.util.logging.Logger;

/**
 * Restaurant world model.
 *
 * The model contains a fixed restaurant floor plan, role-based access rules,
 * two-lane corridors, A* path finding, and dynamic cell reservation. Agents
 * move one cell at a time and never share the same grid cell.
 */
public class RestaurantModel extends GridWorldModel {

    private static final Logger logger = Logger.getLogger(RestaurantModel.class.getName());

    private static final String BOOKED = "booked";
    private static final String COOKING = "cooking";
    private static final int PREEMPTION_MARGIN = 3;

    /** Information sent to a chef whose resources were taken by a higher-priority order. */
    public static final class PreemptionNotice {
        public final String victimOrder;
        public final String victimDish;
        public final String victimChef;
        public final String byOrder;
        public final String byChef;

        PreemptionNotice(String victimOrder, String victimDish, String victimChef,
                         String byOrder, String byChef) {
            this.victimOrder = victimOrder;
            this.victimDish = victimDish;
            this.victimChef = victimChef;
            this.byOrder = byOrder;
            this.byChef = byChef;
        }
    }

    /** Atomic result of trying to book every resource required by one recipe. */
    public static final class ReservationResult {
        public final boolean success;
        public final String reason;
        public final List<PreemptionNotice> preemptions;

        private ReservationResult(boolean success, String reason,
                                  List<PreemptionNotice> preemptions) {
            this.success = success;
            this.reason = reason;
            this.preemptions = preemptions;
        }

        static ReservationResult success(List<PreemptionNotice> preemptions) {
            return new ReservationResult(true, "ok", preemptions);
        }

        static ReservationResult failure(String reason) {
            return new ReservationResult(false, reason,
                    Collections.<PreemptionNotice>emptyList());
        }
    }

    private static final class ResourceBooking {
        final String resource;
        final String orderId;
        final String dish;
        final String chef;
        final int priority;
        String state;

        ResourceBooking(String resource, String orderId, String dish,
                        String chef, int priority, String state) {
            this.resource = resource;
            this.orderId = orderId;
            this.dish = dish;
            this.chef = chef;
            this.priority = priority;
            this.state = state;
        }
    }

    public static final int G_WIDTH = 22;
    public static final int G_HEIGHT = 15;

    private static final int MOVE_STEP_DELAY_MS = 125;
    private static final int BLOCKED_RETRY_MS = 90;
    private static final int ARRIVAL_PAUSE_MS = 120;
    private static final long MAX_BLOCKED_WAIT_MS = 30000L;

    // Visual agent IDs.
    public static final int WAITER1 = 0;
    public static final int WAITER2 = 1;
    public static final int CHEF1 = 2;
    public static final int CHEF2 = 3;
    public static final int CASHIER = 4;
    public static final int CUSTOMER1 = 5;
    public static final int CUSTOMER2 = 6;
    public static final int CUSTOMER3 = 7;
    public static final int AGENT_COUNT = 8;

    // Motion states used by the view.
    public static final int IDLE = 0;
    public static final int MOVING = 1;
    public static final int WAITING = 2;

    // Grid objects. Each value is an independent bit.
    public static final int TABLE1_ZONE = 16;
    public static final int TABLE2_ZONE = 32;
    public static final int KITCHEN_ZONE = 64;
    public static final int STORAGE_ZONE = 128;
    public static final int WAITER_STATION_ZONE = 256;
    public static final int CHEF_STATION_ZONE = 512;
    public static final int PAYMENT_ZONE = 1024;
    public static final int ENTRANCE_ZONE = 2048;
    public static final int CORRIDOR_ZONE = 4096;
    public static final int WALL_OBJECT = 8192;
    public static final int FURNITURE_OBJECT = 16384;
    public static final int DOOR_OBJECT = 32768;
    public static final int PUBLIC_AISLE = 65536;
    public static final int SERVICE_AISLE = 131072;
    public static final int STAFF_AISLE = 262144;

    // Access masks.
    private static final int ACCESS_CUSTOMER = 1;
    private static final int ACCESS_WAITER = 2;
    private static final int ACCESS_CHEF = 4;
    private static final int ACCESS_CASHIER = 8;
    private static final int ACCESS_ALL = ACCESS_CUSTOMER | ACCESS_WAITER
            | ACCESS_CHEF | ACCESS_CASHIER;

    private final boolean[][] walkable = new boolean[G_WIDTH][G_HEIGHT];
    private final int[][] accessMask = new int[G_WIDTH][G_HEIGHT];
    private final int[][] cellObjects = new int[G_WIDTH][G_HEIGHT];

    // Table positions: customers sit at the top, waiters serve from the bottom.
    private final Location waiter1Table1 = new Location(3, 5);
    private final Location waiter2Table1 = new Location(4, 5);
    private final Location waiter1Table2 = new Location(3, 11);
    private final Location waiter2Table2 = new Location(4, 11);

    // Kitchen and storage destinations.
    private final Location waiter1Kitchen = new Location(15, 5);
    private final Location waiter2Kitchen = new Location(16, 5);
    private final Location chef1Kitchen = new Location(15, 2);
    private final Location chef2Kitchen = new Location(18, 2);
    private final Location waiter1Storage = new Location(18, 10);
    private final Location waiter2Storage = new Location(19, 10);

    // Start positions.
    private final Location waiter1Start = new Location(10, 3);
    private final Location waiter2Start = new Location(11, 3);
    private final Location chef1Start = new Location(18, 4);
    private final Location chef2Start = new Location(19, 4);
    private final Location cashierStart = new Location(16, 13);
    private final Location cashierPaymentDesk = new Location(15, 11);

    // Customer positions.
    private final Location customer1Entrance = new Location(8, 14);
    private final Location customer2Entrance = new Location(9, 14);
    private final Location customer3Entrance = new Location(10, 14);
    private final Location customer1Exit = new Location(8, 14);
    private final Location customer2Exit = new Location(9, 14);
    private final Location customer3Exit = new Location(10, 14);
    private final Location customer1SeatTable1 = new Location(3, 2);
    private final Location customer2SeatTable1 = new Location(4, 2);
    private final Location customer1SeatTable2 = new Location(3, 8);
    private final Location customer2SeatTable2 = new Location(4, 8);
    private final Location customer1Payment = new Location(13, 11);
    private final Location customer2Payment = new Location(13, 12);
    private final Location customer3Payment = new Location(12, 13);

    // Recipe resources. Capacity 1 resources are exclusive/bookable; resources
    // with capacity > 1 are genuinely shared by several chefs.
    private final Map<String, List<String>> recipes = new LinkedHashMap<String, List<String>>();
    private final Map<String, Integer> resourceCapacities = new LinkedHashMap<String, Integer>();
    private final Map<String, List<ResourceBooking>> resourceBookings =
            new LinkedHashMap<String, List<ResourceBooking>>();
    private final Map<String, String> orderDishes = new LinkedHashMap<String, String>();
    private final Map<String, Integer> ingredientStock = new LinkedHashMap<String, Integer>();
    private final Map<String, String> alternatives = new LinkedHashMap<String, String>();
    private final Map<String, String> waitingOrders = new LinkedHashMap<String, String>();

    private final Map<String, Boolean> supplyState = new LinkedHashMap<String, Boolean>();
    private final Map<String, Integer> dishOnCounter = new LinkedHashMap<String, Integer>();
    private final Map<String, String> tableOccupants = new LinkedHashMap<String, String>();
    // Transient seating reservations close the race between two nearly simultaneous
    // table-cleaned events. A customer can reserve only one table while walking to it.
    private final Map<String, String> seatingReservations = new LinkedHashMap<String, String>();
    private final Set<String> seatingCustomers = new LinkedHashSet<String>();
    private final Map<String, String> servedDishes = new LinkedHashMap<String, String>();
    private final Map<String, String> orderedDishes = new LinkedHashMap<String, String>();
    private final Map<String, String> tableOrderStatus = new LinkedHashMap<String, String>();
    private final Map<String, LinkedHashSet<String>> tableSupplies =
            new LinkedHashMap<String, LinkedHashSet<String>>();
    private final Set<String> leavingCustomers = new LinkedHashSet<String>();
    private final Set<String> dirtyTables = new LinkedHashSet<String>();

    private final Map<String, Integer> reputations = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> satisfactionScores = new LinkedHashMap<String, Integer>();
    private final Map<String, String> customerMoods = new LinkedHashMap<String, String>();
    private int preemptionCount = 0;
    private int replanCount = 0;
    private int coordinationFailureCount = 0;
    private String lastCoordinationEvent = "System initialised";

    private volatile RestaurantView view;

    private final Object occupancyLock = new Object();
    private final Object[] movementLocks = {
            new Object(), new Object(), new Object(), new Object(),
            new Object(), new Object(), new Object(), new Object()
    };
    private final int[][] occupiedBy = new int[G_WIDTH][G_HEIGHT];
    private final Location[] agentPositions = new Location[AGENT_COUNT];
    private final int[] motionStates = new int[AGENT_COUNT];

    public RestaurantModel() {
        super(G_WIDTH, G_HEIGHT, AGENT_COUNT);

        for (int x = 0; x < G_WIDTH; x++) {
            Arrays.fill(occupiedBy[x], -1);
        }

        createRestaurantLayout();

        placeAgent(WAITER1, waiter1Start);
        placeAgent(WAITER2, waiter2Start);
        placeAgent(CHEF1, chef1Start);
        placeAgent(CHEF2, chef2Start);
        placeAgent(CASHIER, cashierStart);

        // Customers are spawned by their AgentSpeak arrival plans. This makes
        // the visual arrival time match the BDI scenario instead of showing
        // all customers at the entrance from the beginning.
        initialiseRecipesAndResources();

        reputations.put("waiter1", 90);
        reputations.put("waiter2", 88);
        reputations.put("chef1", 91);
        reputations.put("chef2", 89);
        satisfactionScores.put("customer1", 72);
        satisfactionScores.put("customer2", 74);
        satisfactionScores.put("customer3", 70);
        customerMoods.put("customer1", "neutral");
        customerMoods.put("customer2", "neutral");
        customerMoods.put("customer3", "neutral");

        supplyState.put("water", true);
        supplyState.put("napkins", true);
        supplyState.put("cutlery", true);

        tableOccupants.put("table1", null);
        tableOccupants.put("table2", null);
        servedDishes.put("table1", null);
        servedDishes.put("table2", null);
        orderedDishes.put("table1", null);
        orderedDishes.put("table2", null);
        tableOrderStatus.put("table1", "waiting");
        tableOrderStatus.put("table2", "waiting");
        tableSupplies.put("table1", new LinkedHashSet<String>());
        tableSupplies.put("table2", new LinkedHashSet<String>());
    }

    private void initialiseRecipesAndResources() {
        // Physical stock. One unit is consumed when cooking actually starts.
        ingredientStock.put("tomato", 1);
        ingredientStock.put("cheese", 1);
        ingredientStock.put("pasta_base", 1);
        ingredientStock.put("rice", 1);
        ingredientStock.put("stock", 2);
        ingredientStock.put("lettuce", 1);
        ingredientStock.put("vegetables", 2);

        // Ingredient booking capacity follows the remaining physical quantity.
        for (Map.Entry<String, Integer> entry : ingredientStock.entrySet()) {
            resourceCapacities.put(entry.getKey(), entry.getValue());
        }

        // Equipment. Stove and oven are exclusive once cooking begins.
        resourceCapacities.put("prep_counter", 2);
        resourceCapacities.put("stove", 1);
        resourceCapacities.put("oven", 1);

        recipes.put("pasta", Arrays.asList("tomato", "pasta_base", "stove", "prep_counter"));
        recipes.put("pizza", Arrays.asList("tomato", "cheese", "oven", "prep_counter"));
        recipes.put("salad", Arrays.asList("lettuce", "vegetables", "prep_counter"));
        recipes.put("risotto", Arrays.asList("rice", "stock", "stove", "prep_counter"));
        recipes.put("soup", Arrays.asList("stock", "vegetables", "stove", "prep_counter"));

        alternatives.put("pasta", "risotto");
        alternatives.put("pizza", "soup");
        alternatives.put("risotto", "salad");
        alternatives.put("salad", "soup");
        alternatives.put("soup", "salad");

        for (String resource : resourceCapacities.keySet()) {
            resourceBookings.put(resource, new ArrayList<ResourceBooking>());
        }
    }

    /** Builds a restaurant-style plan with public and staff circulation. */
    private void createRestaurantLayout() {
        // Dining rooms.
        addFloorRectangle(TABLE1_ZONE, 1, 1, 7, 5,
                ACCESS_CUSTOMER | ACCESS_WAITER);
        addFloorRectangle(TABLE2_ZONE, 1, 8, 7, 12,
                ACCESS_CUSTOMER | ACCESS_WAITER);

        // Two-lane public aisle (northbound lane x=8, southbound lane x=9).
        addAisleRectangle(PUBLIC_AISLE, 8, 1, 9, 13,
                ACCESS_CUSTOMER | ACCESS_WAITER | ACCESS_CASHIER);

        // Two-lane service aisle (eastbound y=6, westbound y=7).
        addAisleRectangle(SERVICE_AISLE, 1, 6, 20, 7,
                ACCESS_WAITER | ACCESS_CHEF);
        addFloorRectangle(CORRIDOR_ZONE | PUBLIC_AISLE, 8, 6, 9, 7,
                ACCESS_CUSTOMER | ACCESS_WAITER | ACCESS_CHEF | ACCESS_CASHIER);

        // Table approach branches.
        addAisleRectangle(PUBLIC_AISLE, 3, 2, 9, 2,
                ACCESS_CUSTOMER | ACCESS_WAITER);
        addAisleRectangle(SERVICE_AISLE, 3, 5, 9, 5,
                ACCESS_WAITER);
        addAisleRectangle(PUBLIC_AISLE, 3, 8, 9, 8,
                ACCESS_CUSTOMER | ACCESS_WAITER);
        addAisleRectangle(SERVICE_AISLE, 3, 11, 9, 11,
                ACCESS_WAITER);

        // Waiter station connected to the public/service circulation.
        addFloorRectangle(WAITER_STATION_ZONE, 10, 2, 12, 4,
                ACCESS_WAITER);
        addFloorCell(9, 3, CORRIDOR_ZONE | DOOR_OBJECT, ACCESS_WAITER);

        // Kitchen interior and chef work area.
        addFloorRectangle(KITCHEN_ZONE, 14, 1, 20, 5,
                ACCESS_WAITER | ACCESS_CHEF);
        addFloorRectangle(CHEF_STATION_ZONE, 18, 3, 20, 5,
                ACCESS_WAITER | ACCESS_CHEF);
        addFloorCell(15, 6, KITCHEN_ZONE | CORRIDOR_ZONE | DOOR_OBJECT,
                ACCESS_WAITER | ACCESS_CHEF);
        addFloorCell(16, 6, KITCHEN_ZONE | CORRIDOR_ZONE | DOOR_OBJECT,
                ACCESS_WAITER | ACCESS_CHEF);

        // Kitchen walls with a two-cell staff door.
        addWallLine(13, 1, 13, 5);
        addWallLine(13, 0, 20, 0);
        addWallLine(14, 6, 14, 6);
        addWallLine(17, 6, 20, 6);

        // Two-lane staff aisle (northbound x=16, southbound x=17).
        addAisleRectangle(STAFF_AISLE, 16, 6, 17, 13,
                ACCESS_WAITER | ACCESS_CASHIER);

        // Storage room and two-cell service door.
        addFloorRectangle(STORAGE_ZONE, 18, 9, 20, 11,
                ACCESS_WAITER);
        addFloorCell(17, 9, STORAGE_ZONE | CORRIDOR_ZONE | DOOR_OBJECT,
                ACCESS_WAITER);
        addFloorCell(17, 10, STORAGE_ZONE | CORRIDOR_ZONE | DOOR_OBJECT,
                ACCESS_WAITER);
        addWallLine(17, 8, 20, 8);
        addWallLine(17, 12, 20, 12);
        addWallCell(17, 11);

        // Payment area. Customers remain in front of the counter;
        // the cashier stays behind it on the staff side.
        addFloorRectangle(PAYMENT_ZONE, 11, 10, 13, 13,
                ACCESS_CUSTOMER | ACCESS_CASHIER);
        addFloorRectangle(PAYMENT_ZONE, 15, 10, 17, 13,
                ACCESS_CASHIER);
        addAisleRectangle(PUBLIC_AISLE, 8, 11, 13, 12,
                ACCESS_CUSTOMER | ACCESS_CASHIER);

        // Entrance lobby and external doors.
        addFloorRectangle(ENTRANCE_ZONE, 8, 12, 10, 13,
                ACCESS_CUSTOMER | ACCESS_WAITER | ACCESS_CASHIER);
        addFloorCell(8, 14, ENTRANCE_ZONE | DOOR_OBJECT, ACCESS_CUSTOMER);
        addFloorCell(9, 14, ENTRANCE_ZONE | DOOR_OBJECT, ACCESS_CUSTOMER);
        addFloorCell(10, 14, ENTRANCE_ZONE | DOOR_OBJECT, ACCESS_CUSTOMER);

        // Furniture and equipment are true obstacles.
        addFurnitureRectangle(3, 3, 4, 4);      // Table 1
        addFurnitureRectangle(3, 9, 4, 10);     // Table 2
        addFurnitureRectangle(14, 2, 14, 4);    // Kitchen prep counter
        addFurnitureRectangle(20, 2, 20, 4);    // Stove
        addFurnitureRectangle(20, 9, 20, 11);   // Storage shelving
        addFurnitureRectangle(14, 10, 14, 12);  // Payment counter

        // Outer restaurant walls. Entrance cells remain open.
        addWallLine(0, 0, G_WIDTH - 1, 0);
        addWallLine(0, 0, 0, G_HEIGHT - 1);
        addWallLine(G_WIDTH - 1, 0, G_WIDTH - 1, G_HEIGHT - 1);
        addWallLine(0, G_HEIGHT - 1, 7, G_HEIGHT - 1);
        addWallLine(11, G_HEIGHT - 1, G_WIDTH - 1, G_HEIGHT - 1);
    }

    private void addFloorRectangle(int object, int minX, int minY,
                                   int maxX, int maxY, int access) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                addFloorCell(x, y, object, access);
            }
        }
    }

    private void addAisleRectangle(int aisleType, int minX, int minY,
                                   int maxX, int maxY, int access) {
        addFloorRectangle(CORRIDOR_ZONE | aisleType,
                minX, minY, maxX, maxY, access);
    }

    private void addFloorCell(int x, int y, int object, int access) {
        if (!inside(x, y)) return;
        walkable[x][y] = true;
        accessMask[x][y] |= access;
        addObject(object, x, y);
    }

    private void addFurnitureRectangle(int minX, int minY, int maxX, int maxY) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                addObject(FURNITURE_OBJECT, x, y);
                walkable[x][y] = false;
                accessMask[x][y] = 0;
            }
        }
    }

    private void addWallLine(int x1, int y1, int x2, int y2) {
        if (x1 == x2) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                addWallCell(x1, y);
            }
        } else if (y1 == y2) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                addWallCell(x, y1);
            }
        }
    }

    private void addWallCell(int x, int y) {
        if (!inside(x, y)) return;
        addObject(WALL_OBJECT, x, y);
        walkable[x][y] = false;
        accessMask[x][y] = 0;
    }

    private void addObject(int object, int x, int y) {
        if (!inside(x, y)) return;
        cellObjects[x][y] |= object;
        add(object, x, y);
    }

    private boolean inside(int x, int y) {
        return x >= 0 && x < G_WIDTH && y >= 0 && y < G_HEIGHT;
    }

    private void placeAgent(int id, Location location) {
        synchronized (occupancyLock) {
            Location copy = new Location(location.x, location.y);
            agentPositions[id] = copy;
            occupiedBy[copy.x][copy.y] = id;
            setAgPos(id, copy);
        }
    }

    public boolean moveAgent(String agent, Location destination) {
        int id;
        try {
            id = agentId(agent);
        } catch (Exception e) {
            logger.warning("Move failed for " + agent + ": " + e);
            return false;
        }

        synchronized (movementLocks[id]) {
            return animateAgentTo(id, agent, destination);
        }
    }

    /**
     * Replans before every step. This allows a moving agent to avoid another
     * moving agent and to use the free lane of a two-lane aisle.
     */
    private boolean animateAgentTo(int id, String agent, Location destination) {
        if (destination == null) {
            logger.warning("Move failed for " + agent + ": destination is null");
            return false;
        }

        setMotionState(id, MOVING);
        long blockedSince = -1L;
        int completedSteps = 0;

        try {
            while (true) {
                Location current = getAgentPosition(id);
                if (sameCell(current, destination)) {
                    sleepAnimation(ARRIVAL_PAUSE_MS);
                    logger.info("ROUTE: " + agent + " arrived after "
                            + completedSteps + " reserved steps at "
                            + formatLocation(destination));
                    return true;
                }

                List<Location> path = findPath(agent, id, current, destination, true);
                if (path.size() < 2) {
                    if (blockedSince < 0L) blockedSince = System.currentTimeMillis();
                    setMotionState(id, WAITING);
                    if (System.currentTimeMillis() - blockedSince > MAX_BLOCKED_WAIT_MS) {
                        logger.warning("Movement timeout for " + agent + " from "
                                + formatLocation(current) + " to "
                                + formatLocation(destination));
                        return false;
                    }
                    sleepAnimation(BLOCKED_RETRY_MS);
                    continue;
                }

                Location next = path.get(1);
                Location old = null;
                boolean moved = false;

                synchronized (occupancyLock) {
                    Location actual = agentPositions[id];
                    if (actual != null && sameCell(actual, current)
                            && (occupiedBy[next.x][next.y] == -1
                            || occupiedBy[next.x][next.y] == id)) {
                        old = new Location(actual.x, actual.y);
                        occupiedBy[old.x][old.y] = -1;
                        occupiedBy[next.x][next.y] = id;
                        Location copy = new Location(next.x, next.y);
                        agentPositions[id] = copy;
                        setAgPos(id, copy);
                        moved = true;
                    }
                }

                if (!moved) {
                    if (blockedSince < 0L) blockedSince = System.currentTimeMillis();
                    setMotionState(id, WAITING);
                    sleepAnimation(BLOCKED_RETRY_MS);
                    continue;
                }

                blockedSince = -1L;
                completedSteps++;
                setMotionState(id, MOVING);
                notifyAgentMoved(id, old, next);
                sleepAnimation(MOVE_STEP_DELAY_MS);
            }
        } catch (Exception e) {
            logger.warning("Animated move failed for " + agent + ": " + e);
            return false;
        } finally {
            setMotionState(id, IDLE);
            notifyAgentStateChanged(id);
        }
    }

    /** A* route search with access control, lane preference, and occupancy. */
    private List<Location> findPath(String agent, int id, Location start,
                                    Location destination, boolean avoidAgents) {
        if (!inside(destination.x, destination.y)
                || !walkable[destination.x][destination.y]
                || !isAllowed(agent, destination.x, destination.y)) {
            return Collections.emptyList();
        }

        double[][] best = new double[G_WIDTH][G_HEIGHT];
        for (int x = 0; x < G_WIDTH; x++) {
            Arrays.fill(best[x], Double.POSITIVE_INFINITY);
        }
        Location[][] previous = new Location[G_WIDTH][G_HEIGHT];

        PriorityQueue<PathNode> open = new PriorityQueue<PathNode>(
                Comparator.comparingDouble((PathNode node) -> node.f)
                        .thenComparingDouble(node -> node.g)
                        .thenComparingInt(node -> node.y)
                        .thenComparingInt(node -> node.x));

        best[start.x][start.y] = 0.0;
        open.add(new PathNode(start.x, start.y, 0.0,
                heuristic(start.x, start.y, destination)));

        final int[][] directions = {
                {1, 0}, {0, 1}, {-1, 0}, {0, -1}
        };

        while (!open.isEmpty()) {
            PathNode current = open.poll();
            if (current.g > best[current.x][current.y] + 0.0001) continue;

            if (current.x == destination.x && current.y == destination.y) {
                return rebuildPath(previous, start, destination);
            }

            for (int[] direction : directions) {
                int nx = current.x + direction[0];
                int ny = current.y + direction[1];
                if (!inside(nx, ny) || !walkable[nx][ny]
                        || !isAllowed(agent, nx, ny)) {
                    continue;
                }
                if (avoidAgents && isOccupiedByOther(nx, ny, id)) {
                    continue;
                }

                double nextG = current.g
                        + movementCost(agent, current.x, current.y, nx, ny, destination)
                        + nearbyAgentPenalty(nx, ny, id);
                if (nextG + 0.0001 < best[nx][ny]) {
                    best[nx][ny] = nextG;
                    previous[nx][ny] = new Location(current.x, current.y);
                    double f = nextG + heuristic(nx, ny, destination);
                    open.add(new PathNode(nx, ny, nextG, f));
                }
            }
        }

        return Collections.emptyList();
    }

    private double movementCost(String agent, int fromX, int fromY,
                                int x, int y, Location destination) {
        if (x == destination.x && y == destination.y) return 0.70;

        int objects = cellObjects[x][y];
        double cost;
        if ((objects & CORRIDOR_ZONE) != 0) {
            cost = 1.0;
        } else if ((objects & DOOR_OBJECT) != 0) {
            cost = 1.0;
        } else if ((objects & ENTRANCE_ZONE) != 0) {
            cost = 1.05;
        } else if ((objects & KITCHEN_ZONE) != 0
                || (objects & STORAGE_ZONE) != 0
                || (objects & WAITER_STATION_ZONE) != 0
                || (objects & CHEF_STATION_ZONE) != 0
                || (objects & PAYMENT_ZONE) != 0) {
            cost = 1.25;
        } else {
            cost = 2.6; // Open room floor is allowed, but aisles are preferred.
        }

        // Lane discipline lowers head-on conflicts.
        int dx = x - fromX;
        int dy = y - fromY;
        if ((objects & SERVICE_AISLE) != 0 && dx != 0) {
            boolean preferred = (dx > 0 && y == 6) || (dx < 0 && y == 7);
            if (!preferred) cost += 0.42;
        }
        if ((objects & PUBLIC_AISLE) != 0 && dy != 0 && (x == 8 || x == 9)) {
            boolean preferred = (dy < 0 && x == 8) || (dy > 0 && x == 9);
            if (!preferred) cost += 0.35;
        }
        if ((objects & STAFF_AISLE) != 0 && dy != 0 && (x == 16 || x == 17)) {
            boolean preferred = (dy < 0 && x == 16) || (dy > 0 && x == 17);
            if (!preferred) cost += 0.35;
        }

        return cost;
    }

    private double nearbyAgentPenalty(int x, int y, int id) {
        double penalty = 0.0;
        synchronized (occupancyLock) {
            int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] direction : directions) {
                int nx = x + direction[0];
                int ny = y + direction[1];
                if (inside(nx, ny) && occupiedBy[nx][ny] >= 0
                        && occupiedBy[nx][ny] != id) {
                    penalty += 0.18;
                }
            }
        }
        return penalty;
    }

    private boolean isOccupiedByOther(int x, int y, int id) {
        synchronized (occupancyLock) {
            return occupiedBy[x][y] >= 0 && occupiedBy[x][y] != id;
        }
    }

    private double heuristic(int x, int y, Location destination) {
        return Math.abs(destination.x - x) + Math.abs(destination.y - y);
    }

    private List<Location> rebuildPath(Location[][] previous,
                                       Location start, Location destination) {
        List<Location> reversed = new ArrayList<Location>();
        Location current = new Location(destination.x, destination.y);
        reversed.add(current);

        while (!sameCell(current, start)) {
            Location parent = previous[current.x][current.y];
            if (parent == null) return Collections.emptyList();
            current = new Location(parent.x, parent.y);
            reversed.add(current);
        }

        Collections.reverse(reversed);
        return reversed;
    }

    private boolean isAllowed(String agent, int x, int y) {
        int required;
        if ("customer1".equals(agent) || "customer2".equals(agent) || "customer3".equals(agent)) {
            required = ACCESS_CUSTOMER;
        } else if ("waiter1".equals(agent) || "waiter2".equals(agent)) {
            required = ACCESS_WAITER;
        } else if ("chef1".equals(agent) || "chef2".equals(agent)) {
            required = ACCESS_CHEF;
        } else if ("cashier".equals(agent)) {
            required = ACCESS_CASHIER;
        } else {
            return false;
        }
        return (accessMask[x][y] & required) != 0;
    }

    private boolean sameCell(Location a, Location b) {
        return a != null && b != null && a.x == b.x && a.y == b.y;
    }

    private String formatLocation(Location location) {
        return "(" + location.x + "," + location.y + ")";
    }

    private static final class PathNode {
        final int x;
        final int y;
        final double g;
        final double f;

        PathNode(int x, int y, double g, double f) {
            this.x = x;
            this.y = y;
            this.g = g;
            this.f = f;
        }
    }

    private void sleepAnimation(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized Location resolveDestination(String agent, String location) {
        if ("waiter1".equals(agent) || "waiter2".equals(agent)) {
            if ("table1".equals(location)) {
                return "waiter1".equals(agent) ? waiter1Table1 : waiter2Table1;
            }
            if ("table2".equals(location)) {
                return "waiter1".equals(agent) ? waiter1Table2 : waiter2Table2;
            }
            if ("kitchen".equals(location)) {
                return "waiter1".equals(agent) ? waiter1Kitchen : waiter2Kitchen;
            }
            if ("storage".equals(location)) {
                return "waiter1".equals(agent) ? waiter1Storage : waiter2Storage;
            }
            if ("start".equals(location)) {
                return "waiter1".equals(agent) ? waiter1Start : waiter2Start;
            }
        }

        if ("chef1".equals(agent)) {
            if ("kitchen".equals(location)) return chef1Kitchen;
            if ("start".equals(location)) return chef1Start;
        }

        if ("chef2".equals(agent)) {
            if ("kitchen".equals(location)) return chef2Kitchen;
            if ("start".equals(location)) return chef2Start;
        }

        if ("cashier".equals(agent)) {
            if ("paymentDesk".equals(location)) return cashierPaymentDesk;
            if ("start".equals(location)) return cashierStart;
        }

        return null;
    }

    private int agentId(String name) {
        if ("waiter1".equals(name)) return WAITER1;
        if ("waiter2".equals(name)) return WAITER2;
        if ("chef1".equals(name)) return CHEF1;
        if ("chef2".equals(name)) return CHEF2;
        if ("cashier".equals(name)) return CASHIER;
        if ("customer1".equals(name)) return CUSTOMER1;
        if ("customer2".equals(name)) return CUSTOMER2;
        if ("customer3".equals(name)) return CUSTOMER3;
        throw new IllegalArgumentException("Unknown agent: " + name);
    }

    /** Makes a customer visible at the entrance when its BDI arrival plan fires. */
    public boolean customerArrive(String customer) {
        int id;
        Location entrance;
        try {
            id = agentId(customer);
        } catch (Exception e) {
            logger.warning("Arrival failed for " + customer + ": " + e);
            return false;
        }

        if ("customer1".equals(customer)) {
            entrance = customer1Entrance;
        } else if ("customer2".equals(customer)) {
            entrance = customer2Entrance;
        } else if ("customer3".equals(customer)) {
            entrance = customer3Entrance;
        } else {
            logger.warning("Unknown customer arrival: " + customer);
            return false;
        }

        synchronized (occupancyLock) {
            if (agentPositions[id] != null) return true;
        }
        placeAgent(id, entrance);
        notifyAgentMoved(id, null, entrance);
        lastCoordinationEvent = customer + " arrived at the entrance";
        refreshKitchen();
        return true;
    }

    private void hideCustomer(String customer) {
        int id = agentId(customer);
        Location oldLocation;
        synchronized (occupancyLock) {
            oldLocation = agentPositions[id];
            if (oldLocation != null && inside(oldLocation.x, oldLocation.y)
                    && occupiedBy[oldLocation.x][oldLocation.y] == id) {
                occupiedBy[oldLocation.x][oldLocation.y] = -1;
            }
            agentPositions[id] = null;
            motionStates[id] = IDLE;
        }
        notifyAgentMoved(id, oldLocation, null);
    }

    /**
     * Seats a customer transactionally and race-safely.  The reservation is
     * committed before the (slow) walking animation starts, so two concurrent
     * Manager intentions can never seat the same customer at two tables or two
     * customers at the same table.  The reservation is rolled back if movement
     * fails; the final table occupant is committed only after the seat is reached.
     */
    public boolean seatCustomer(String customer, String table) {
        Location seat = customerSeat(customer, table);
        int id;
        try {
            id = agentId(customer);
        } catch (Exception e) {
            logger.warning("Cannot seat unknown customer " + customer);
            return false;
        }

        synchronized (this) {
            if (seat == null || !tableOccupants.containsKey(table)) {
                logger.warning("Cannot seat " + customer + " at " + table);
                return false;
            }
            if (dirtyTables.contains(table) || tableOccupants.get(table) != null
                    || seatingReservations.containsKey(table)) {
                logger.warning("Cannot seat " + customer + " at unavailable/reserved " + table);
                return false;
            }
            // A customer that is already seated (or currently walking to a seat)
            // must never be accepted for another table.
            for (Map.Entry<String, String> e : tableOccupants.entrySet()) {
                if (customer.equals(e.getValue())) {
                    logger.warning("Duplicate seating rejected: " + customer
                            + " is already at " + e.getKey());
                    return false;
                }
            }
            if (seatingCustomers.contains(customer)) {
                logger.warning("Duplicate seating rejected while " + customer
                        + " is already walking to a reserved table");
                return false;
            }

            seatingReservations.put(table, customer);
            seatingCustomers.add(customer);
            lastCoordinationEvent = customer + " reserved " + table + " for seating";
        }
        refreshKitchen();

        boolean moved;
        synchronized (movementLocks[id]) {
            moved = animateAgentTo(id, customer, seat);
        }

        synchronized (this) {
            String reservationOwner = seatingReservations.get(table);
            if (!customer.equals(reservationOwner)) {
                seatingCustomers.remove(customer);
                logger.warning("Seat reservation disappeared for " + customer + " / " + table);
                return false;
            }

            if (!moved || dirtyTables.contains(table) || tableOccupants.get(table) != null) {
                seatingReservations.remove(table);
                seatingCustomers.remove(customer);
                lastCoordinationEvent = "Seating rolled back for " + customer + " at " + table;
                refreshKitchen();
                return false;
            }

            seatingReservations.remove(table);
            seatingCustomers.remove(customer);
            leavingCustomers.remove(customer);
            dirtyTables.remove(table);
            tableOccupants.put(table, customer);
            servedDishes.put(table, null);
            orderedDishes.put(table, null);
            tableOrderStatus.put(table, "waiting");
            tableSupplies.get(table).clear();
            lastCoordinationEvent = customer + " seated at " + table;
        }
        refreshTable(table);
        refreshKitchen();
        return true;
    }

    public boolean moveCustomerToPayment(String customer) {
        Location destination;
        if ("customer1".equals(customer)) {
            destination = customer1Payment;
        } else if ("customer2".equals(customer)) {
            destination = customer2Payment;
        } else if ("customer3".equals(customer)) {
            destination = customer3Payment;
        } else {
            logger.warning("Unknown customer: " + customer);
            return false;
        }

        int id = agentId(customer);
        synchronized (movementLocks[id]) {
            return animateAgentTo(id, customer, destination);
        }
    }

    /**
     * Vacates a table immediately after successful payment.  This is the logical
     * state transition OCCUPIED -> DIRTY and is deliberately separate from the
     * customer's animated walk to the exit.  Cleaning therefore never depends on
     * whether an exit animation is delayed by traffic.
     */
    public synchronized boolean vacateTable(String customer, String table) {
        if (!tableOccupants.containsKey(table) || !customer.equals(tableOccupants.get(table))) {
            logger.warning("Cannot vacate " + table + " for " + customer);
            return false;
        }
        leavingCustomers.add(customer);
        tableOccupants.put(table, null);
        dirtyTables.add(table);
        tableOrderStatus.put(table, "dirty");
        lastCoordinationEvent = table + " became DIRTY after " + customer + " paid";
        refreshTable(table);
        refreshKitchen();
        return true;
    }

    /** Walks a customer to a dedicated exit and then removes the icon. */
    public boolean customerLeave(String customer) {
        Location exit;
        if ("customer1".equals(customer)) {
            exit = customer1Exit;
        } else if ("customer2".equals(customer)) {
            exit = customer2Exit;
        } else if ("customer3".equals(customer)) {
            exit = customer3Exit;
        } else {
            logger.warning("Unknown customer: " + customer);
            return false;
        }

        int id = agentId(customer);
        boolean moved;
        synchronized (movementLocks[id]) {
            moved = animateAgentTo(id, customer, exit);
        }
        if (!moved) {
            logger.warning("Exit animation failed for " + customer + "; removing departed guest anyway.");
        }

        synchronized (this) {
            leavingCustomers.remove(customer);
            lastCoordinationEvent = customer + " left the restaurant";
        }
        refreshKitchen();
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        hideCustomer(customer);
        // Departure is logically complete even if only the optional animation failed.
        return true;
    }

    private Location customerSeat(String customer, String table) {
        if ("table1".equals(table)) {
            if ("customer2".equals(customer)) return customer2SeatTable1;
            if ("customer1".equals(customer) || "customer3".equals(customer)) {
                return customer1SeatTable1;
            }
        }
        if ("table2".equals(table)) {
            if ("customer2".equals(customer)) return customer2SeatTable2;
            if ("customer1".equals(customer) || "customer3".equals(customer)) {
                return customer1SeatTable2;
            }
        }
        return null;
    }

    /**
     * Atomically books every ingredient/equipment item required by a recipe.
     * A high-priority order can preempt lower-priority BOOKED resources, but
     * never resources whose owner has already entered the COOKING state.
     */
    public synchronized ReservationResult reserveRecipe(String dish, String chef,
                                                        String orderId, int priority) {
        List<String> required = recipes.get(dish);
        if (required == null) {
            coordinationFailureCount++;
            lastCoordinationEvent = "Unknown recipe " + dish;
            refreshKitchen();
            return ReservationResult.failure("unknown_recipe");
        }

        if (hasAllBookings(orderId, chef, required)) {
            return ReservationResult.success(Collections.<PreemptionNotice>emptyList());
        }

        Set<String> victimOrders = new LinkedHashSet<String>();
        for (String resource : required) {
            int capacity = resourceCapacities.get(resource).intValue();
            if (capacity <= 0) {
                waitingOrders.remove(orderId);
                coordinationFailureCount++;
                lastCoordinationEvent = "Out of stock: " + dish + " needs " + resource;
                refreshKitchen();
                refreshStorage();
                return ReservationResult.failure("out_of_stock");
            }
            List<ResourceBooking> holders = activeBookings(resource, orderId, victimOrders);

            while (holders.size() >= capacity) {
                ResourceBooking candidate = lowestPreemptable(holders, priority);
                if (candidate == null) {
                    coordinationFailureCount++;
                    waitingOrders.put(orderId, chef + "/" + dish + " P" + priority
                            + " waiting for " + resource);
                    lastCoordinationEvent = "Booking waits: " + dish + " needs "
                            + resource + " (priority " + priority + ")";
                    refreshKitchen();
                    return ReservationResult.failure("resource_busy");
                }
                victimOrders.add(candidate.orderId);
                holders = activeBookings(resource, orderId, victimOrders);
            }
        }

        List<PreemptionNotice> notices = new ArrayList<PreemptionNotice>();
        for (String victimOrder : victimOrders) {
            ResourceBooking victim = firstBookingForOrder(victimOrder);
            if (victim != null) {
                notices.add(new PreemptionNotice(victim.orderId, victim.dish, victim.chef,
                        orderId, chef));
            }
            releaseOrderInternal(victimOrder);
        }

        for (String resource : required) {
            resourceBookings.get(resource).add(new ResourceBooking(
                    resource, orderId, dish, chef, priority, BOOKED));
        }
        orderDishes.put(orderId, dish);
        waitingOrders.remove(orderId);

        if (!notices.isEmpty()) {
            preemptionCount += notices.size();
            lastCoordinationEvent = chef + " preempted " + notices.size()
                    + " lower-priority booking(s) for " + dish + " P" + priority;
            logger.info("PREEMPTION: " + lastCoordinationEvent);
        } else {
            lastCoordinationEvent = chef + " booked resources for " + dish
                    + " (" + orderId + ", P" + priority + ")";
            logger.info("BOOKING: " + lastCoordinationEvent);
        }
        refreshKitchen();
        return ReservationResult.success(notices);
    }

    private List<ResourceBooking> activeBookings(String resource, String requesterOrder,
                                                  Set<String> ignoredVictims) {
        List<ResourceBooking> result = new ArrayList<ResourceBooking>();
        for (ResourceBooking booking : resourceBookings.get(resource)) {
            if (requesterOrder.equals(booking.orderId)) continue;
            if (ignoredVictims.contains(booking.orderId)) continue;
            result.add(booking);
        }
        return result;
    }

    private ResourceBooking lowestPreemptable(List<ResourceBooking> bookings,
                                               int requesterPriority) {
        ResourceBooking best = null;
        for (ResourceBooking booking : bookings) {
            boolean marginSatisfied = requesterPriority >= booking.priority + PREEMPTION_MARGIN;
            if (!BOOKED.equals(booking.state) || !marginSatisfied) continue;
            if (best == null || booking.priority < best.priority) best = booking;
        }
        return best;
    }

    private boolean hasAllBookings(String orderId, String chef, List<String> required) {
        for (String resource : required) {
            boolean found = false;
            for (ResourceBooking booking : resourceBookings.get(resource)) {
                if (orderId.equals(booking.orderId) && chef.equals(booking.chef)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private ResourceBooking firstBookingForOrder(String orderId) {
        for (List<ResourceBooking> bookings : resourceBookings.values()) {
            for (ResourceBooking booking : bookings) {
                if (orderId.equals(booking.orderId)) return booking;
            }
        }
        return null;
    }

    public synchronized boolean startCooking(String orderId, String chef) {
        String dish = orderDishes.get(orderId);
        List<String> required = recipes.get(dish);
        if (required == null || !hasAllBookings(orderId, chef, required)) {
            coordinationFailureCount++;
            lastCoordinationEvent = chef + " could not start " + orderId
                    + ": reservation lost";
            refreshKitchen();
            return false;
        }

        // Consume ingredients exactly once at the transition BOOKED -> COOKING.
        for (String resource : required) {
            if (!ingredientStock.containsKey(resource)) continue;
            int current = ingredientStock.get(resource).intValue();
            if (current <= 0) {
                coordinationFailureCount++;
                lastCoordinationEvent = chef + " could not start " + dish
                        + ": " + resource + " is out of stock";
                refreshKitchen();
                refreshStorage();
                return false;
            }
        }

        for (String resource : required) {
            if (!ingredientStock.containsKey(resource)) continue;
            int newQuantity = ingredientStock.get(resource).intValue() - 1;
            ingredientStock.put(resource, Integer.valueOf(newQuantity));
            resourceCapacities.put(resource, Integer.valueOf(newQuantity));

            // The physical ingredient has been consumed, so its booking no longer
            // remains as a lock. Equipment bookings remain and become COOKING.
            List<ResourceBooking> bookings = resourceBookings.get(resource);
            for (int i = bookings.size() - 1; i >= 0; i--) {
                ResourceBooking booking = bookings.get(i);
                if (orderId.equals(booking.orderId) && chef.equals(booking.chef)) {
                    bookings.remove(i);
                }
            }
        }

        for (List<ResourceBooking> bookings : resourceBookings.values()) {
            for (ResourceBooking booking : bookings) {
                if (orderId.equals(booking.orderId) && chef.equals(booking.chef)) {
                    booking.state = COOKING;
                }
            }
        }
        waitingOrders.remove(orderId);
        lastCoordinationEvent = chef + " committed equipment and started " + dish;
        refreshKitchen();
        refreshStorage();
        return true;
    }

    public synchronized boolean releaseRecipe(String orderId, String chef) {
        waitingOrders.remove(orderId);
        boolean removed = false;
        for (List<ResourceBooking> bookings : resourceBookings.values()) {
            for (int i = bookings.size() - 1; i >= 0; i--) {
                ResourceBooking booking = bookings.get(i);
                if (orderId.equals(booking.orderId) && chef.equals(booking.chef)) {
                    bookings.remove(i);
                    removed = true;
                }
            }
        }
        orderDishes.remove(orderId);
        if (removed) {
            lastCoordinationEvent = chef + " released resources for " + orderId;
            refreshKitchen();
        }
        return true;
    }

    private void releaseOrderInternal(String orderId) {
        for (List<ResourceBooking> bookings : resourceBookings.values()) {
            for (int i = bookings.size() - 1; i >= 0; i--) {
                if (orderId.equals(bookings.get(i).orderId)) bookings.remove(i);
            }
        }
        orderDishes.remove(orderId);
    }

    public boolean prepareDish(String dish, String orderId, String chef) {
        synchronized (this) {
            if (!ownsCookingReservation(orderId, chef, dish)) {
                coordinationFailureCount++;
                lastCoordinationEvent = chef + " lost resources before cooking " + dish;
                refreshKitchen();
                return false;
            }
        }
        try {
            Thread.sleep(2600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        synchronized (this) {
            if (!ownsCookingReservation(orderId, chef, dish)) {
                coordinationFailureCount++;
                lastCoordinationEvent = chef + " cooking aborted after resource loss";
                refreshKitchen();
                return false;
            }
            Integer count = dishOnCounter.get(dish);
            dishOnCounter.put(dish, count == null ? 1 : count + 1);
            lastCoordinationEvent = chef + " completed " + dish + " for " + orderId;
        }
        logger.info("Cooked: " + dish + " for " + orderId);
        refreshKitchen();
        return true;
    }

    private boolean ownsCookingReservation(String orderId, String chef, String dish) {
        List<String> required = recipes.get(dish);
        if (required == null) return false;
        for (String resource : required) {
            if (ingredientStock.containsKey(resource)) continue;
            boolean found = false;
            for (ResourceBooking booking : resourceBookings.get(resource)) {
                if (orderId.equals(booking.orderId) && chef.equals(booking.chef)
                        && COOKING.equals(booking.state)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Compatibility wrapper retained for older plans. */
    public boolean prepareDish(String dish) {
        try {
            Thread.sleep(2600);
            synchronized (this) {
                Integer count = dishOnCounter.get(dish);
                dishOnCounter.put(dish, count == null ? 1 : count + 1);
            }
            refreshKitchen();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public synchronized boolean pickupDish(String dish) {
        Integer storedCount = dishOnCounter.get(dish);
        int count = storedCount == null ? 0 : storedCount;
        if (count <= 0) {
            logger.warning("No " + dish + " is ready on the counter.");
            return false;
        }
        dishOnCounter.put(dish, count - 1);
        refreshKitchen();
        return true;
    }

    public synchronized boolean serveDish(String dish, String table) {
        if (table != null && servedDishes.containsKey(table)) {
            servedDishes.put(table, dish);
            orderedDishes.put(table, dish);
            tableOrderStatus.put(table, "served");
        }
        logger.info("Served: " + dish + (table == null ? "" : " at " + table));
        refreshTable(table);
        return true;
    }

    public synchronized boolean takeSupply(String item) {
        Boolean value = supplyState.get(item);
        boolean available = value != null && value.booleanValue();
        if (!available) {
            logger.warning("Supply unavailable in model: " + item);
            return false;
        }
        supplyState.put(item, Boolean.FALSE);
        refreshStorage();
        return true;
    }

    /**
     * Places a supply on a table only while the intended customer is still
     * seated there. This atomic validation prevents stale deliveries.
     */
    public synchronized boolean deliverSupply(String item, String table, String customer) {
        String occupant = tableOccupants.get(table);
        if (!customer.equals(occupant) || leavingCustomers.contains(customer)) {
            coordinationFailureCount++;
            lastCoordinationEvent = "Cancelled stale " + item + " delivery for " + customer;
            logger.info("STALE DELIVERY BLOCKED: " + item + " for " + customer
                    + " at " + table);
            refreshTable(table);
            refreshKitchen();
            return false;
        }
        LinkedHashSet<String> items = tableSupplies.get(table);
        if (items != null) items.add(item);
        lastCoordinationEvent = item + " placed on " + table + " for " + customer;
        logger.info("Supply placed on table: " + item + " at " + table);
        refreshTable(table);
        refreshKitchen();
        return true;
    }

    /** Compatibility wrapper retained for older plans. */
    public boolean deliverSupply(String item) {
        logger.info("Supply delivered without table binding: " + item);
        return true;
    }

    /** Returns a cancelled/rescinded supply to storage. */
    public synchronized boolean returnSupply(String item) {
        if (!supplyState.containsKey(item)) return false;
        supplyState.put(item, Boolean.TRUE);
        lastCoordinationEvent = item + " returned to storage after cancellation";
        logger.info("Supply returned to storage: " + item);
        refreshStorage();
        refreshKitchen();
        return true;
    }

    /** Updates the visible order ticket placed on a dining table. */
    public synchronized boolean setTableOrder(String table, String dish, String status) {
        if (!tableOccupants.containsKey(table)) return false;
        orderedDishes.put(table, "none".equals(dish) ? null : dish);
        tableOrderStatus.put(table, status);
        refreshTable(table);
        return true;
    }

    /**
     * Returns a reusable table item to storage before the table is cleared.
     * Consumables such as water and napkins are intentionally not reclaimed.
     */
    public synchronized boolean reclaimReusableSupply(String table, String item) {
        LinkedHashSet<String> items = tableSupplies.get(table);
        if (items == null || !items.contains(item)) return false;
        if (!"cutlery".equals(item)) return false;
        items.remove(item);
        supplyState.put(item, Boolean.TRUE);
        lastCoordinationEvent = item + " reclaimed from " + table + " during cleaning";
        refreshTable(table);
        refreshStorage();
        return true;
    }

    /** Clears dishes and table items after a waiter has physically cleaned a dirty table. */
    public synchronized boolean cleanTable(String table, String waiter) {
        if (!dirtyTables.contains(table)) {
            logger.warning(waiter + " tried to clean non-dirty " + table);
            return false;
        }
        servedDishes.put(table, null);
        orderedDishes.put(table, null);
        tableOrderStatus.put(table, "idle");
        LinkedHashSet<String> items = tableSupplies.get(table);
        if (items != null) items.clear();
        dirtyTables.remove(table);
        lastCoordinationEvent = waiter + " cleaned " + table + "; table is ready";
        logger.info("Table cleaned: " + table + " by " + waiter);
        refreshTable(table);
        refreshKitchen();
        return true;
    }

    public synchronized boolean isTableDirty(String table) {
        return dirtyTables.contains(table);
    }

    public boolean receivePayment(String customer, int amount) {
        // Keep the presentation deterministic.  Payment-failure recovery still
        // exists in AgentSpeak and can be tested separately without random demos.
        logger.info("Payment accepted for " + customer + ": " + amount);
        return true;
    }

    public synchronized String getTableOccupant(String table) {
        return tableOccupants.get(table);
    }

    /** Customer that has atomically reserved this table while walking to it. */
    public synchronized String getTableReservation(String table) {
        return seatingReservations.get(table);
    }

    public synchronized String getServedDish(String table) {
        return servedDishes.get(table);
    }

    public synchronized String getOrderedDish(String table) {
        return orderedDishes.get(table);
    }

    public synchronized String getTableOrderStatus(String table) {
        return tableOrderStatus.get(table);
    }

    public synchronized Set<String> getTableSuppliesSnapshot(String table) {
        LinkedHashSet<String> items = tableSupplies.get(table);
        if (items == null) return Collections.emptySet();
        return new LinkedHashSet<String>(items);
    }

    public synchronized boolean isCustomerSeatedAt(String customer, String table) {
        return customer.equals(tableOccupants.get(table))
                && !leavingCustomers.contains(customer);
    }

    public synchronized boolean isCustomerLeaving(String customer) {
        return leavingCustomers.contains(customer);
    }

    public synchronized String getTableItemsSummary(String table) {
        StringBuilder result = new StringBuilder();
        String dish = servedDishes.get(table);
        if (dish != null) result.append(dish);
        LinkedHashSet<String> items = tableSupplies.get(table);
        if (items != null) {
            for (String item : items) {
                if (result.length() > 0) result.append(", ");
                result.append(item);
            }
        }
        return result.length() == 0 ? "empty" : result.toString();
    }

    public synchronized boolean canPrepareDish(String dish) {
        return getMissingIngredients(dish).isEmpty();
    }

    public synchronized List<String> getMissingIngredients(String dish) {
        List<String> missing = new ArrayList<String>();
        List<String> required = recipes.get(dish);
        if (required == null) return missing;
        for (String resource : required) {
            Integer quantity = ingredientStock.get(resource);
            if (quantity != null && quantity.intValue() <= 0) {
                missing.add(resource);
            }
        }
        return missing;
    }

    public synchronized String findAvailableAlternative(String dish) {
        String alternative = alternatives.get(dish);
        if (alternative != null && canPrepareDish(alternative)) return alternative;
        return null;
    }

    public synchronized Map<String, Integer> getIngredientStockSnapshot() {
        return new LinkedHashMap<String, Integer>(ingredientStock);
    }

    public synchronized String getStationSummary() {
        String[] stations = {"stove", "oven", "prep_counter"};
        StringBuilder result = new StringBuilder();
        for (String station : stations) {
            if (result.length() > 0) result.append(" | ");
            List<ResourceBooking> bookings = resourceBookings.get(station);
            result.append(station).append(":");
            if (bookings == null || bookings.isEmpty()) {
                result.append("FREE");
            } else {
                for (int i = 0; i < bookings.size(); i++) {
                    if (i > 0) result.append(",");
                    ResourceBooking b = bookings.get(i);
                    result.append(b.chef).append("/").append(b.dish)
                            .append(" ").append(b.state.toUpperCase());
                }
            }
        }
        if (!waitingOrders.isEmpty()) {
            result.append(" | WAIT:");
            boolean first = true;
            for (String value : waitingOrders.values()) {
                if (!first) result.append(", ");
                result.append(value);
                first = false;
            }
        }
        return result.toString();
    }

    public synchronized Map<String, Integer> getReadyDishesSnapshot() {
        return new LinkedHashMap<String, Integer>(dishOnCounter);
    }

    public synchronized String getActiveLocksSummary() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, List<ResourceBooking>> entry : resourceBookings.entrySet()) {
            List<ResourceBooking> bookings = entry.getValue();
            if (bookings.isEmpty()) continue;
            if (result.length() > 0) result.append(" | ");
            result.append(entry.getKey()).append("[");
            for (int i = 0; i < bookings.size(); i++) {
                ResourceBooking b = bookings.get(i);
                if (i > 0) result.append(",");
                result.append(b.chef).append("/").append(b.dish)
                        .append(" P").append(b.priority)
                        .append(" ").append(b.state);
            }
            result.append("]");
        }
        return result.length() == 0 ? "No booked/shared resources" : result.toString();
    }

    public synchronized void setReputation(String agent, int value) {
        reputations.put(agent, Integer.valueOf(value));
        refreshKitchen();
    }

    public synchronized void setSatisfaction(String customer, int score, String mood) {
        satisfactionScores.put(customer, Integer.valueOf(score));
        customerMoods.put(customer, mood);
        lastCoordinationEvent = customer + " experience: " + score + " / " + mood;
        refreshKitchen();
    }

    public synchronized void recordReplan(String agent, String orderId, String reason) {
        replanCount++;
        lastCoordinationEvent = agent + " re-planned " + orderId + " (" + reason + ")";
        refreshKitchen();
    }

    public synchronized String getReputationSummary() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : reputations.entrySet()) {
            if (result.length() > 0) result.append(" | ");
            result.append(entry.getKey()).append(":").append(entry.getValue());
        }
        return result.toString();
    }

    public synchronized String getSatisfactionSummary() {
        StringBuilder result = new StringBuilder();
        for (String customer : satisfactionScores.keySet()) {
            if (result.length() > 0) result.append(" | ");
            result.append(customer).append(":")
                    .append(satisfactionScores.get(customer)).append(" ")
                    .append(customerMoods.get(customer));
        }
        return result.toString();
    }

    public synchronized String getCoordinationSummary() {
        return "preemptions=" + preemptionCount + " | replans=" + replanCount
                + " | failures=" + coordinationFailureCount;
    }

    public synchronized String getLastCoordinationEvent() {
        return lastCoordinationEvent;
    }

    public synchronized String getReadyDishSummary() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : dishOnCounter.entrySet()) {
            if (entry.getValue() != null && entry.getValue().intValue() > 0) {
                if (result.length() > 0) result.append(" | ");
                result.append(entry.getKey()).append(" × ").append(entry.getValue());
            }
        }
        return result.length() == 0 ? "Counter is empty" : result.toString();
    }

    public synchronized String getSupplySummary() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Boolean> entry : supplyState.entrySet()) {
            if (result.length() > 0) result.append(" | ");
            result.append(entry.getKey()).append(": ")
                    .append(Boolean.TRUE.equals(entry.getValue()) ? "available" : "depleted");
        }
        return result.toString();
    }

    public synchronized Map<String, Boolean> getSupplyAvailabilitySnapshot() {
        return new LinkedHashMap<String, Boolean>(supplyState);
    }

    public boolean isWalkableCell(int x, int y) {
        return inside(x, y) && walkable[x][y];
    }

    public int getCellObjectsAt(int x, int y) {
        return inside(x, y) ? cellObjects[x][y] : 0;
    }

    public Location[] getAgentPositionsSnapshot() {
        synchronized (occupancyLock) {
            Location[] snapshot = new Location[AGENT_COUNT];
            for (int i = 0; i < AGENT_COUNT; i++) {
                Location position = agentPositions[i];
                snapshot[i] = position == null ? null
                        : new Location(position.x, position.y);
            }
            return snapshot;
        }
    }

    public int[] getMotionStatesSnapshot() {
        synchronized (occupancyLock) {
            return Arrays.copyOf(motionStates, motionStates.length);
        }
    }

    private Location getAgentPosition(int id) {
        synchronized (occupancyLock) {
            Location position = agentPositions[id];
            return new Location(position.x, position.y);
        }
    }

    private void setMotionState(int id, int state) {
        boolean changed;
        synchronized (occupancyLock) {
            changed = motionStates[id] != state;
            motionStates[id] = state;
        }
        if (changed) notifyAgentStateChanged(id);
    }

    public int getMovingCount() {
        synchronized (occupancyLock) {
            int count = 0;
            for (int state : motionStates) {
                if (state == MOVING) count++;
            }
            return count;
        }
    }

    public int getWaitingCount() {
        synchronized (occupancyLock) {
            int count = 0;
            for (int state : motionStates) {
                if (state == WAITING) count++;
            }
            return count;
        }
    }

    public void setView(RestaurantView view) {
        this.view = view;
    }

    private void notifyAgentMoved(int id, Location oldLocation, Location newLocation) {
        RestaurantView currentView = view;
        if (currentView != null) {
            currentView.agentMoved(id, oldLocation, newLocation);
        }
    }

    private void notifyAgentStateChanged(int id) {
        RestaurantView currentView = view;
        if (currentView != null) {
            currentView.agentStateChanged(id);
        }
    }

    private void refreshKitchen() {
        RestaurantView currentView = view;
        if (currentView != null) currentView.refreshKitchen();
    }

    private void refreshStorage() {
        RestaurantView currentView = view;
        if (currentView != null) currentView.refreshStorage();
    }

    private void refreshTable(String table) {
        RestaurantView currentView = view;
        if (currentView != null && table != null) {
            currentView.refreshTable(table);
        }
    }
}
