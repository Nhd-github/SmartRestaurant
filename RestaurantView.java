import jason.environment.grid.Location;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;

/**
 * Layered restaurant view.
 *
 * The floor plan is rendered once into a BufferedImage. Agent movement only
 * repaints the previous and next cells, so the background does not flash or
 * rebuild on every movement step. All repaint requests are marshalled onto the
 * Swing Event Dispatch Thread.
 */
public class RestaurantView {

    private static final int CELL = 42;
    private static final int GRID_LEFT = 18;
    private static final int GRID_TOP = 68;
    private static final int SIDEBAR_GAP = 18;
    private static final int SIDEBAR_WIDTH = 270;
    private static final int BOTTOM_MARGIN = 24;

    private static final int GRID_PIXEL_WIDTH = RestaurantModel.G_WIDTH * CELL;
    private static final int GRID_PIXEL_HEIGHT = RestaurantModel.G_HEIGHT * CELL;
    private static final int PANEL_WIDTH = GRID_LEFT + GRID_PIXEL_WIDTH
            + SIDEBAR_GAP + SIDEBAR_WIDTH + 18;
    private static final int PANEL_HEIGHT = GRID_TOP + GRID_PIXEL_HEIGHT
            + BOTTOM_MARGIN;

    private final RestaurantModel model;
    private JFrame frame;
    private RestaurantPanel panel;

    public RestaurantView(RestaurantModel model) {
        this.model = model;
        runOnEdtAndWait(this::buildUi);
    }

    private void buildUi() {
        frame = new JFrame("Smart Restaurant MAS - Hard Coordination Demo");
        panel = new RestaurantPanel();
        panel.setPreferredSize(new Dimension(PANEL_WIDTH, PANEL_HEIGHT));
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public void agentMoved(int id, Location oldLocation, Location newLocation) {
        SwingUtilities.invokeLater(() -> {
            if (panel == null) return;
            if (oldLocation != null) panel.repaint(cellDirtyRect(oldLocation));
            if (newLocation != null) panel.repaint(cellDirtyRect(newLocation));
            panel.repaint(sidebarTrafficRect());
        });
    }

    public void agentStateChanged(int id) {
        SwingUtilities.invokeLater(() -> {
            if (panel == null) return;
            Location[] positions = model.getAgentPositionsSnapshot();
            if (id >= 0 && id < positions.length && positions[id] != null) {
                panel.repaint(cellDirtyRect(positions[id]));
            }
            panel.repaint(sidebarTrafficRect());
        });
    }

    public void refreshStatus() {
        SwingUtilities.invokeLater(() -> {
            if (panel != null) panel.repaint(sidebarStatusRect());
        });
    }

    public void refreshKitchen() {
        SwingUtilities.invokeLater(() -> {
            if (panel == null) return;
            panel.repaint(gridRect(14, 1, 20, 6));
            panel.repaint(sidebarStatusRect());
        });
    }

    public void refreshStorage() {
        SwingUtilities.invokeLater(() -> {
            if (panel == null) return;
            panel.repaint(gridRect(17, 8, 20, 12));
            panel.repaint(sidebarStatusRect());
        });
    }

    public void refreshTable(String table) {
        SwingUtilities.invokeLater(() -> {
            if (panel == null) return;
            if ("table1".equals(table)) {
                panel.repaint(gridRect(1, 1, 7, 5));
            } else if ("table2".equals(table)) {
                panel.repaint(gridRect(1, 8, 7, 12));
            }
            panel.repaint(sidebarStatusRect());
        });
    }

    private Rectangle cellDirtyRect(Location location) {
        int pad = 7;
        return new Rectangle(
                GRID_LEFT + location.x * CELL - pad,
                GRID_TOP + location.y * CELL - pad,
                CELL + pad * 2,
                CELL + pad * 2);
    }

    private Rectangle gridRect(int minX, int minY, int maxX, int maxY) {
        return new Rectangle(
                GRID_LEFT + minX * CELL,
                GRID_TOP + minY * CELL,
                (maxX - minX + 1) * CELL,
                (maxY - minY + 1) * CELL);
    }

    private Rectangle sidebarStatusRect() {
        return new Rectangle(GRID_LEFT + GRID_PIXEL_WIDTH + SIDEBAR_GAP,
                GRID_TOP + 52, SIDEBAR_WIDTH, GRID_PIXEL_HEIGHT - 52);
    }

    private Rectangle sidebarTrafficRect() {
        return new Rectangle(GRID_LEFT + GRID_PIXEL_WIDTH + SIDEBAR_GAP,
                GRID_TOP + 548, SIDEBAR_WIDTH, 76);
    }

    private void runOnEdtAndWait(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create restaurant view", e);
        }
    }

    private final class RestaurantPanel extends JPanel {

        private static final long serialVersionUID = 1L;

        private final Font titleFont = new Font("SansSerif", Font.BOLD, 22);
        private final Font subtitleFont = new Font("SansSerif", Font.PLAIN, 12);
        private final Font roomFont = new Font("SansSerif", Font.BOLD, 13);
        private final Font labelFont = new Font("SansSerif", Font.BOLD, 11);
        private final Font bodyFont = new Font("SansSerif", Font.PLAIN, 11);
        private final Font smallFont = new Font("SansSerif", Font.PLAIN, 10);
        private final Font agentFont = new Font("SansSerif", Font.BOLD, 11);

        private BufferedImage staticLayer;

        RestaurantPanel() {
            setOpaque(true);
            setBackground(new Color(239, 241, 244));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                enableQuality(g);
                ensureStaticLayer();
                g.drawImage(staticLayer, 0, 0, null);
                // Read one coherent dynamic world state per paint. Without this
                // lock, a payment/cleaning transition could occur between drawing
                // the table badge and the sidebar, briefly showing DIRTY in one
                // place and OCCUPIED in the other.
                synchronized (model) {
                    drawDynamicTableOverlays(g);
                    drawKitchenCounterItems(g);
                    drawStorageInventory(g);
                    drawAgents(g);
                    drawSidebarValues(g);
                }
            } finally {
                g.dispose();
            }
        }

        private void ensureStaticLayer() {
            if (staticLayer != null) return;
            staticLayer = new BufferedImage(PANEL_WIDTH, PANEL_HEIGHT,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = staticLayer.createGraphics();
            try {
                enableQuality(g);
                drawStaticScene(g);
            } finally {
                g.dispose();
            }
        }

        private void drawStaticScene(Graphics2D g) {
            g.setPaint(new GradientPaint(0, 0, new Color(248, 249, 251),
                    0, PANEL_HEIGHT, new Color(230, 234, 238)));
            g.fillRect(0, 0, PANEL_WIDTH, PANEL_HEIGHT);

            g.setFont(titleFont);
            g.setColor(new Color(34, 43, 54));
            g.drawString("SMART RESTAURANT MULTI-AGENT SYSTEM", GRID_LEFT, 31);
            g.setFont(subtitleFont);
            g.setColor(new Color(94, 103, 113));
            g.drawString("Adaptive CNP • atomic booking • controlled preemption • BDI re-planning",
                    GRID_LEFT, 51);

            drawGridFloor(g);
            drawFurnitureAndFixtures(g);
            drawRoomLabels(g);
            drawLaneGuides(g);
            drawSidebarCards(g);
        }

        private void drawGridFloor(Graphics2D g) {
            Color outside = new Color(206, 211, 215);
            Color dining1 = new Color(229, 240, 246);
            Color dining2 = new Color(232, 243, 232);
            Color kitchen = new Color(248, 225, 207);
            Color storage = new Color(239, 232, 210);
            Color waiterStation = new Color(221, 233, 245);
            Color chefStation = new Color(245, 214, 195);
            Color payment = new Color(234, 226, 246);
            Color entrance = new Color(221, 240, 230);
            Color publicAisle = new Color(239, 226, 196);
            Color serviceAisle = new Color(224, 228, 232);
            Color staffAisle = new Color(217, 224, 229);
            Color roomFloor = new Color(244, 242, 236);
            Color gridLine = new Color(181, 188, 192, 90);

            g.setColor(new Color(255, 255, 255));
            g.fillRoundRect(GRID_LEFT - 7, GRID_TOP - 7,
                    GRID_PIXEL_WIDTH + 14, GRID_PIXEL_HEIGHT + 14, 18, 18);
            g.setColor(new Color(0, 0, 0, 28));
            g.drawRoundRect(GRID_LEFT - 7, GRID_TOP - 7,
                    GRID_PIXEL_WIDTH + 14, GRID_PIXEL_HEIGHT + 14, 18, 18);

            for (int y = 0; y < RestaurantModel.G_HEIGHT; y++) {
                for (int x = 0; x < RestaurantModel.G_WIDTH; x++) {
                    int objects = model.getCellObjectsAt(x, y);
                    Color color;
                    if ((objects & RestaurantModel.WALL_OBJECT) != 0) {
                        color = new Color(76, 67, 61);
                    } else if (!model.isWalkableCell(x, y) && objects == 0) {
                        color = outside;
                    } else if ((objects & RestaurantModel.SERVICE_AISLE) != 0) {
                        color = serviceAisle;
                    } else if ((objects & RestaurantModel.STAFF_AISLE) != 0) {
                        color = staffAisle;
                    } else if ((objects & RestaurantModel.PUBLIC_AISLE) != 0
                            || (objects & RestaurantModel.CORRIDOR_ZONE) != 0) {
                        color = publicAisle;
                    } else if ((objects & RestaurantModel.TABLE1_ZONE) != 0) {
                        color = dining1;
                    } else if ((objects & RestaurantModel.TABLE2_ZONE) != 0) {
                        color = dining2;
                    } else if ((objects & RestaurantModel.CHEF_STATION_ZONE) != 0) {
                        color = chefStation;
                    } else if ((objects & RestaurantModel.KITCHEN_ZONE) != 0) {
                        color = kitchen;
                    } else if ((objects & RestaurantModel.STORAGE_ZONE) != 0) {
                        color = storage;
                    } else if ((objects & RestaurantModel.WAITER_STATION_ZONE) != 0) {
                        color = waiterStation;
                    } else if ((objects & RestaurantModel.PAYMENT_ZONE) != 0) {
                        color = payment;
                    } else if ((objects & RestaurantModel.ENTRANCE_ZONE) != 0) {
                        color = entrance;
                    } else {
                        color = roomFloor;
                    }

                    int px = cellX(x);
                    int py = cellY(y);
                    g.setColor(color);
                    g.fillRect(px, py, CELL, CELL);

                    if ((objects & RestaurantModel.WALL_OBJECT) != 0) {
                        g.setColor(new Color(111, 96, 85));
                        g.drawLine(px + 4, py + 6, px + CELL - 5, py + 6);
                    } else if (model.isWalkableCell(x, y)) {
                        g.setColor(gridLine);
                        g.drawRect(px, py, CELL, CELL);
                    }

                    if ((objects & RestaurantModel.DOOR_OBJECT) != 0) {
                        drawDoor(g, px, py);
                    }
                }
            }
        }

        private void drawFurnitureAndFixtures(Graphics2D g) {
            drawDiningTable(g, 3, 3, 2, 2, new Color(150, 103, 63));
            drawDiningTable(g, 3, 9, 2, 2, new Color(140, 99, 64));

            // Chairs and service positions.
            drawChair(g, 3, 2, new Color(82, 116, 137));
            drawChair(g, 4, 2, new Color(82, 116, 137));
            drawChair(g, 3, 5, new Color(166, 111, 56));
            drawChair(g, 4, 5, new Color(166, 111, 56));
            drawChair(g, 3, 8, new Color(82, 116, 137));
            drawChair(g, 4, 8, new Color(82, 116, 137));
            drawChair(g, 3, 11, new Color(166, 111, 56));
            drawChair(g, 4, 11, new Color(166, 111, 56));

            drawCounter(g, 14, 2, 1, 3, new Color(191, 150, 111), "PREP");
            drawStove(g, 20, 2, 1, 3);
            drawShelves(g, 20, 9, 1, 3);
            drawCounter(g, 14, 10, 1, 3, new Color(112, 87, 137), "PAY");

            // Kitchen pass counter.
            int passX = cellX(15);
            int passY = cellY(5) + CELL - 9;
            g.setColor(new Color(175, 128, 90));
            g.fillRoundRect(passX, passY, CELL * 2, 12, 7, 7);
            g.setColor(new Color(112, 75, 50));
            g.drawRoundRect(passX, passY, CELL * 2, 12, 7, 7);
        }

        private void drawRoomLabels(Graphics2D g) {
            drawRoomTitle(g, "DINING • TABLE 1", 1, 1, 7, new Color(51, 91, 116));
            drawRoomTitle(g, "DINING • TABLE 2", 1, 8, 7, new Color(55, 105, 69));
            drawRoomTitle(g, "WAITER STATION", 10, 2, 3, new Color(51, 90, 125));
            drawRoomTitle(g, "KITCHEN • STAFF ONLY", 14, 1, 7, new Color(133, 71, 41));
            drawRoomTitle(g, "STORAGE", 18, 9, 3, new Color(112, 91, 46));
            drawRoomTitle(g, "PAYMENT DESK", 11, 10, 7, new Color(88, 59, 117));
            drawRoomTitle(g, "ENTRANCE / EXIT", 8, 13, 3, new Color(47, 104, 78));

            g.setFont(smallFont);
            g.setColor(new Color(102, 110, 116));
            g.drawString("PUBLIC AISLE", cellX(8) + 4, cellY(4) + 18);
            g.drawString("SERVICE AISLE →", cellX(10) + 4, cellY(6) + 18);
            g.drawString("← SERVICE AISLE", cellX(10) + 4, cellY(7) + 18);
            g.drawString("STAFF AISLE", cellX(16) + 3, cellY(8) + 18);
        }

        private void drawLaneGuides(Graphics2D g) {
            g.setStroke(new BasicStroke(1.4f));
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            g.setColor(new Color(108, 116, 121, 95));

            for (int x = 2; x <= 19; x += 3) {
                drawArrow(g, cellX(x) + CELL / 2, cellY(6) + CELL / 2,
                        true, true);
                drawArrow(g, cellX(x) + CELL / 2, cellY(7) + CELL / 2,
                        true, false);
            }
            for (int y = 2; y <= 12; y += 3) {
                drawArrow(g, cellX(8) + CELL / 2, cellY(y) + CELL / 2,
                        false, false);
                drawArrow(g, cellX(9) + CELL / 2, cellY(y) + CELL / 2,
                        false, true);
            }
            for (int y = 7; y <= 12; y += 2) {
                drawArrow(g, cellX(16) + CELL / 2, cellY(y) + CELL / 2,
                        false, false);
                drawArrow(g, cellX(17) + CELL / 2, cellY(y) + CELL / 2,
                        false, true);
            }
        }

        private void drawSidebarCards(Graphics2D g) {
            int x = sidebarX();
            g.setColor(new Color(255, 255, 255, 235));
            g.fillRoundRect(x, GRID_TOP, SIDEBAR_WIDTH, GRID_PIXEL_HEIGHT, 18, 18);
            g.setColor(new Color(0, 0, 0, 28));
            g.drawRoundRect(x, GRID_TOP, SIDEBAR_WIDTH, GRID_PIXEL_HEIGHT, 18, 18);

            g.setFont(roomFont);
            g.setColor(new Color(45, 52, 61));
            g.drawString("HARD COORDINATION STATUS", x + 18, GRID_TOP + 31);
            g.setFont(smallFont);
            g.setColor(new Color(112, 119, 126));
            g.drawString("Resources may be booked, shared, or preempted.",
                    x + 18, GRID_TOP + 49);

            drawCardShell(g, x + 14, GRID_TOP + 62, SIDEBAR_WIDTH - 28, 94,
                    new Color(231, 241, 247), "TABLE 1");
            drawCardShell(g, x + 14, GRID_TOP + 164, SIDEBAR_WIDTH - 28, 94,
                    new Color(233, 244, 233), "TABLE 2");
            drawCardShell(g, x + 14, GRID_TOP + 266, SIDEBAR_WIDTH - 28, 100,
                    new Color(250, 231, 215), "BOOKED / SHARED RESOURCES");
            drawCardShell(g, x + 14, GRID_TOP + 374, SIDEBAR_WIDTH - 28, 82,
                    new Color(236, 232, 248), "REPUTATION & SATISFACTION");
            drawCardShell(g, x + 14, GRID_TOP + 464, SIDEBAR_WIDTH - 28, 92,
                    new Color(247, 237, 218), "COORDINATION EVENTS");
            drawCardShell(g, x + 14, GRID_TOP + 564, SIDEBAR_WIDTH - 28, 52,
                    new Color(232, 236, 240), "TRAFFIC");
        }

        private void drawDynamicTableOverlays(Graphics2D g) {
            drawDynamicTableBadges(g);
            drawTableItems(g, "table1", 3, 3);
            drawTableItems(g, "table2", 3, 9);
        }

        /** Draws physical objects that are currently on a dining table. */
        private void drawTableItems(Graphics2D g, String table, int gx, int gy) {
            String occupant = model.getTableOccupant(table);
            String servedDish = model.getServedDish(table);
            String orderedDish = model.getOrderedDish(table);
            String orderStatus = model.getTableOrderStatus(table);
            Set<String> supplies = model.getTableSuppliesSnapshot(table);

            int x = cellX(gx) + 5;
            int y = cellY(gy) + 5;
            int w = CELL * 2 - 10;
            int h = CELL * 2 - 10;

            // A paper order ticket stays on the table until the meal is served.
            if (occupant != null && servedDish == null && orderedDish != null) {
                drawOrderTicket(g, x + w / 2 - 19, y + h / 2 - 15,
                        orderedDish, orderStatus);
            }

            if (servedDish != null) {
                drawDishPlate(g, x + w / 2, y + h / 2, 38, servedDish);
            }
            if (supplies.contains("water")) {
                drawWaterGlass(g, x + 8, y + 12);
            }
            if (supplies.contains("napkins")) {
                drawNapkin(g, x + w - 24, y + 12);
            }
            if (supplies.contains("cutlery")) {
                drawCutlery(g, x + 8, y + h - 24);
            }
        }

        private void drawOrderTicket(Graphics2D g, int x, int y,
                                     String dish, String status) {
            g.setColor(new Color(0, 0, 0, 35));
            g.fillRoundRect(x + 2, y + 3, 38, 30, 5, 5);
            g.setColor(new Color(255, 252, 231));
            g.fillRoundRect(x, y, 38, 30, 5, 5);
            g.setColor(new Color(174, 151, 101));
            g.drawRoundRect(x, y, 38, 30, 5, 5);
            g.setFont(new Font("SansSerif", Font.BOLD, 8));
            g.setColor(new Color(91, 74, 46));
            String shortDish = dish.length() <= 5 ? dish.toUpperCase()
                    : dish.substring(0, 5).toUpperCase();
            drawCentered(g, shortDish, x, y + 2, 38, 12);
            g.setFont(new Font("SansSerif", Font.PLAIN, 7));
            String shortStatus = status == null ? "ORDERED" : status.toUpperCase();
            drawCentered(g, shortStatus, x, y + 14, 38, 12);
        }

        private void drawDishPlate(Graphics2D g, int cx, int cy,
                                   int diameter, String dish) {
            int left = cx - diameter / 2;
            int top = cy - diameter / 2;
            g.setColor(new Color(0, 0, 0, 35));
            g.fillOval(left + 2, top + 3, diameter, diameter);
            g.setColor(new Color(250, 250, 247));
            g.fillOval(left, top, diameter, diameter);
            g.setColor(new Color(176, 179, 178));
            g.drawOval(left, top, diameter, diameter);
            g.setColor(new Color(235, 236, 232));
            g.drawOval(left + 4, top + 4, diameter - 8, diameter - 8);

            String d = dish.toLowerCase();
            if ("pizza".equals(d)) {
                g.setColor(new Color(224, 170, 70));
                g.fillOval(left + 7, top + 7, diameter - 14, diameter - 14);
                g.setColor(new Color(188, 62, 45));
                for (int i = 0; i < 4; i++) {
                    int px = left + 11 + (i % 2) * 11;
                    int py = top + 11 + (i / 2) * 11;
                    g.fillOval(px, py, 5, 5);
                }
            } else if ("pasta".equals(d)) {
                g.setColor(new Color(224, 185, 83));
                for (int i = 0; i < 6; i++) {
                    g.drawArc(left + 8, top + 8 + i * 3, diameter - 16, 10, 0, 180);
                }
                g.setColor(new Color(185, 69, 45));
                g.fillOval(cx - 5, cy - 5, 10, 10);
            } else if ("risotto".equals(d)) {
                g.setColor(new Color(229, 211, 150));
                g.fillOval(left + 7, top + 9, diameter - 14, diameter - 18);
                g.setColor(new Color(114, 151, 83));
                for (int i = 0; i < 8; i++) {
                    g.fillOval(left + 10 + (i * 7) % 20,
                            top + 12 + (i * 5) % 16, 3, 3);
                }
            } else if ("salad".equals(d)) {
                g.setColor(new Color(84, 157, 86));
                for (int i = 0; i < 7; i++) {
                    int px = left + 8 + (i * 9) % 23;
                    int py = top + 9 + (i * 7) % 20;
                    g.fillOval(px, py, 8, 5);
                }
                g.setColor(new Color(204, 70, 56));
                g.fillOval(cx - 3, cy - 3, 6, 6);
            } else if ("soup".equals(d)) {
                g.setColor(new Color(195, 108, 54));
                g.fillOval(left + 7, top + 8, diameter - 14, diameter - 16);
                g.setColor(new Color(244, 211, 107));
                g.fillOval(cx - 2, cy - 4, 4, 4);
            } else {
                g.setColor(new Color(211, 176, 94));
                g.fillOval(left + 8, top + 8, diameter - 16, diameter - 16);
            }
        }

        private void drawWaterGlass(Graphics2D g, int x, int y) {
            g.setColor(new Color(255, 255, 255, 210));
            g.fillRoundRect(x, y, 13, 23, 4, 4);
            g.setColor(new Color(80, 156, 203, 185));
            g.fillRoundRect(x + 2, y + 9, 9, 12, 3, 3);
            g.setColor(new Color(86, 120, 139));
            g.drawRoundRect(x, y, 13, 23, 4, 4);
        }

        private void drawNapkin(Graphics2D g, int x, int y) {
            int[] xs = {x + 8, x + 18, x + 10, x};
            int[] ys = {y, y + 9, y + 19, y + 10};
            g.setColor(new Color(247, 247, 240));
            g.fillPolygon(xs, ys, 4);
            g.setColor(new Color(130, 149, 158));
            g.drawPolygon(xs, ys, 4);
            g.drawLine(x + 4, y + 7, x + 13, y + 13);
        }

        private void drawCutlery(Graphics2D g, int x, int y) {
            g.setColor(new Color(115, 121, 124));
            g.setStroke(new BasicStroke(1.3f));
            g.drawLine(x + 3, y + 2, x + 3, y + 19);
            g.drawLine(x, y + 2, x, y + 8);
            g.drawLine(x + 2, y + 2, x + 2, y + 8);
            g.drawLine(x + 4, y + 2, x + 4, y + 8);
            g.drawLine(x + 11, y + 2, x + 11, y + 19);
            g.drawLine(x + 11, y + 2, x + 15, y + 7);
        }

        /** Plates appear on the pass when chefs finish and disappear on pickup. */
        private void drawKitchenCounterItems(Graphics2D g) {
            Map<String, Integer> ready = model.getReadyDishesSnapshot();
            int index = 0;
            for (Map.Entry<String, Integer> entry : ready.entrySet()) {
                int count = entry.getValue() == null ? 0 : entry.getValue().intValue();
                for (int i = 0; i < count && index < 4; i++, index++) {
                    int cx = cellX(15) + 12 + index * 18;
                    int cy = cellY(5) + CELL - 7;
                    drawDishPlate(g, cx, cy, 14, entry.getKey());
                }
            }
        }

        /** Shows both food-ingredient quantities and table-supply stock. */
        private void drawStorageInventory(Graphics2D g) {
            Map<String, Integer> ingredients = model.getIngredientStockSnapshot();
            int x = cellX(18) + 5;
            int y = cellY(9) + 8;

            g.setFont(new Font("SansSerif", Font.BOLD, 9));
            g.setColor(new Color(83, 67, 42));
            g.drawString("FOOD STOCK", x, y);

            g.setFont(new Font("SansSerif", Font.PLAIN, 8));
            int row = 0;
            for (Map.Entry<String, Integer> entry : ingredients.entrySet()) {
                int qty = entry.getValue() == null ? 0 : entry.getValue().intValue();
                g.setColor(qty > 0 ? new Color(76, 85, 62) : new Color(170, 61, 52));
                String name = entry.getKey().replace('_', ' ');
                g.drawString(name + " x" + qty, x, y + 13 + row * 13);
                row++;
            }

            Map<String, Boolean> stock = model.getSupplyAvailabilitySnapshot();
            String[] names = {"water", "napkins", "cutlery"};
            String[] codes = {"W", "N", "C"};
            for (int i = 0; i < names.length; i++) {
                boolean available = Boolean.TRUE.equals(stock.get(names[i]));
                int bx = cellX(20) + 10;
                int by = cellY(9 + i) + 11;
                g.setColor(available ? new Color(241, 216, 142)
                        : new Color(150, 137, 118));
                g.fillRoundRect(bx, by, 18, 18, 5, 5);
                g.setColor(new Color(86, 70, 48));
                g.drawRoundRect(bx, by, 18, 18, 5, 5);
                g.setFont(new Font("SansSerif", Font.BOLD, 9));
                drawCentered(g, codes[i], bx, by, 18, 18);
            }
        }

        private void drawDynamicTableBadges(Graphics2D g) {
            drawTableBadge(g, "table1", 1, 1, new Color(50, 92, 117));
            drawTableBadge(g, "table2", 1, 8, new Color(54, 106, 69));
        }

        private void drawTableBadge(Graphics2D g, String table,
                                    int gridX, int gridY, Color accent) {
            String occupant = model.getTableOccupant(table);
            String reservation = model.getTableReservation(table);
            String served = model.getServedDish(table);
            String ordered = model.getOrderedDish(table);
            String status = model.getTableOrderStatus(table);
            boolean leaving = occupant != null && model.isCustomerLeaving(occupant);
            boolean dirty = model.isTableDirty(table);
            boolean reserved = !dirty && occupant == null && reservation != null;
            String state = dirty ? "DIRTY"
                    : (reserved ? "RESERVED"
                    : (occupant == null ? "FREE" : (leaving ? "LEAVING" : "OCCUPIED")));
            String meal = served != null ? served + " served"
                    : (ordered == null ? "waiting for order" : ordered + " • " + status);
            String second = dirty ? "Waiting for waiter cleaning"
                    : (reserved ? reservation + " • walking to table"
                    : (occupant == null ? "Ready for guest" : occupant + " • " + meal));

            int x = cellX(gridX) + 8;
            int y = cellY(gridY) + 24;
            int width = CELL * 6 - 16;
            int height = 35;
            g.setColor(new Color(255, 255, 255, 225));
            g.fillRoundRect(x, y, width, height, 12, 12);
            g.setColor(accent);
            g.fillRoundRect(x, y, 70, height, 12, 12);
            g.fillRect(x + 58, y, 12, height);
            g.setFont(labelFont);
            g.setColor(Color.WHITE);
            g.drawString(state, x + 8, y + 22);
            g.setFont(smallFont);
            g.setColor(new Color(57, 63, 70));
            drawClippedText(g, second, x + 78, y + 22, width - 86);
        }

        private void drawSidebarValues(Graphics2D g) {
            int x = sidebarX() + 26;

            drawTableStatusText(g, "table1", x, GRID_TOP + 96);
            drawTableStatusText(g, "table2", x, GRID_TOP + 198);

            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g.setColor(new Color(63, 67, 72));
            // Fixed baselines prevent long coordination summaries from painting
            // on top of one another.  Full details remain visible in the console.
            drawClippedText(g, "Stations: " + model.getStationSummary(), x, GRID_TOP + 300,
                    SIDEBAR_WIDTH - 52);
            drawClippedText(g, "Locks: " + model.getActiveLocksSummary(), x, GRID_TOP + 326,
                    SIDEBAR_WIDTH - 52);
            drawClippedText(g, "Pass: " + model.getReadyDishSummary(), x, GRID_TOP + 352,
                    SIDEBAR_WIDTH - 52);

            drawWrappedLine(g, "Reputation: " + model.getReputationSummary(),
                    x, GRID_TOP + 417, SIDEBAR_WIDTH - 52);
            drawWrappedLine(g, "Customers: " + model.getSatisfactionSummary(),
                    x, GRID_TOP + 443, SIDEBAR_WIDTH - 52);

            g.setFont(labelFont);
            g.setColor(new Color(132, 83, 28));
            g.drawString(model.getCoordinationSummary(), x, GRID_TOP + 501);
            g.setFont(smallFont);
            g.setColor(new Color(75, 70, 62));
            drawWrappedLine(g, "Last: " + model.getLastCoordinationEvent(),
                    x, GRID_TOP + 529, SIDEBAR_WIDTH - 52);

            int moving = model.getMovingCount();
            int waiting = model.getWaitingCount();
            g.setFont(labelFont);
            g.setColor(new Color(53, 61, 70));
            g.drawString("Moving: " + moving + "   Waiting: " + waiting,
                    x, GRID_TOP + 596);
        }

        private void drawTableStatusText(Graphics2D g, String table, int x, int y) {
            String occupant = model.getTableOccupant(table);
            String reservation = model.getTableReservation(table);
            String served = model.getServedDish(table);
            String ordered = model.getOrderedDish(table);
            String status = model.getTableOrderStatus(table);
            boolean leaving = occupant != null && model.isCustomerLeaving(occupant);
            boolean dirty = model.isTableDirty(table);
            boolean reserved = !dirty && occupant == null && reservation != null;
            g.setFont(labelFont);
            g.setColor(dirty ? new Color(176, 78, 53)
                    : (reserved ? new Color(76, 98, 166)
                    : (occupant == null ? new Color(48, 126, 80)
                    : new Color(180, 107, 36))));
            String tableState = dirty ? "DIRTY"
                    : (reserved ? "RESERVED"
                    : (occupant == null ? "FREE" : (leaving ? "LEAVING" : "OCCUPIED")));
            g.drawString(tableState, x, y);
            g.setFont(bodyFont);
            g.setColor(new Color(67, 72, 78));
            String customerLine = occupant != null ? occupant : (reserved ? reservation : "—");
            g.drawString("Customer: " + customerLine, x, y + 19);
            String orderLine = dirty ? "waiting for cleaning"
                    : (reserved ? "awaiting seating"
                    : (occupant == null ? "—"
                    : (served != null ? served + " served"
                    : (ordered == null ? "waiting for order" : ordered + " / " + status))));
            g.drawString("Order: " + orderLine, x, y + 37);
            g.drawString("On table: " + model.getTableItemsSummary(table), x, y + 55);
        }

        private void drawAgents(Graphics2D g) {
            Location[] positions = model.getAgentPositionsSnapshot();
            int[] states = model.getMotionStatesSnapshot();
            for (int id = 0; id < positions.length; id++) {
                Location location = positions[id];
                if (location == null) continue;
                drawAgent(g, id, location.x, location.y, states[id]);
            }
        }

        private void drawAgent(Graphics2D g, int id, int gridX, int gridY, int state) {
            String label;
            Color color;
            int shape;
            if (id == RestaurantModel.WAITER1) {
                label = "W1"; color = new Color(65, 126, 180); shape = 0;
            } else if (id == RestaurantModel.WAITER2) {
                label = "W2"; color = new Color(48, 164, 105); shape = 0;
            } else if (id == RestaurantModel.CHEF1) {
                label = "C1"; color = new Color(222, 74, 44); shape = 1;
            } else if (id == RestaurantModel.CHEF2) {
                label = "C2"; color = new Color(203, 122, 25); shape = 1;
            } else if (id == RestaurantModel.CASHIER) {
                label = "CA"; color = new Color(139, 101, 211); shape = 2;
            } else if (id == RestaurantModel.CUSTOMER1) {
                label = "U1"; color = new Color(33, 151, 184); shape = 3;
            } else if (id == RestaurantModel.CUSTOMER2) {
                label = "U2"; color = new Color(229, 83, 138); shape = 3;
            } else {
                label = "U3"; color = new Color(118, 94, 183); shape = 3;
            }

            int px = cellX(gridX);
            int py = cellY(gridY);
            int size = 27;
            int left = px + (CELL - size) / 2;
            int top = py + (CELL - size) / 2;

            if (state == RestaurantModel.WAITING) {
                g.setColor(new Color(235, 154, 42, 150));
                g.setStroke(new BasicStroke(2.2f));
                g.drawOval(left - 5, top - 5, size + 10, size + 10);
            } else if (state == RestaurantModel.MOVING) {
                g.setColor(new Color(255, 255, 255, 190));
                g.setStroke(new BasicStroke(1.5f));
                g.drawOval(left - 3, top - 3, size + 6, size + 6);
            }

            g.setColor(new Color(0, 0, 0, 40));
            g.fillOval(left + 2, top + 3, size, size);
            g.setColor(color);
            if (shape == 0) {
                g.fillOval(left, top, size, size);
            } else if (shape == 1) {
                g.fillRoundRect(left, top, size, size, 9, 9);
            } else if (shape == 2) {
                int[] xs = {left + size / 2, left + size, left + size / 2, left};
                int[] ys = {top, top + size / 2, top + size, top + size / 2};
                g.fillPolygon(xs, ys, 4);
            } else {
                int head = 9;
                g.fillOval(left + (size - head) / 2, top, head, head);
                g.fillRoundRect(left + 6, top + 8, size - 12, size - 8, 8, 8);
            }
            g.setFont(agentFont);
            g.setColor(Color.WHITE);
            drawCentered(g, label, left, top, size, size);
        }

        private void drawDiningTable(Graphics2D g, int gx, int gy,
                                     int widthCells, int heightCells, Color color) {
            int x = cellX(gx) + 5;
            int y = cellY(gy) + 5;
            int w = widthCells * CELL - 10;
            int h = heightCells * CELL - 10;
            g.setColor(new Color(0, 0, 0, 36));
            g.fillRoundRect(x + 4, y + 5, w, h, 22, 22);
            g.setColor(color);
            g.fillRoundRect(x, y, w, h, 22, 22);
            g.setColor(color.darker());
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(x, y, w, h, 22, 22);
            g.setColor(new Color(246, 242, 230));
            g.fillOval(x + w / 2 - 9, y + h / 2 - 9, 18, 18);
        }

        private void drawChair(Graphics2D g, int gx, int gy, Color color) {
            int x = cellX(gx) + 12;
            int y = cellY(gy) + 12;
            g.setColor(new Color(0, 0, 0, 30));
            g.fillRoundRect(x + 2, y + 3, 20, 20, 7, 7);
            g.setColor(color);
            g.fillRoundRect(x, y, 20, 20, 7, 7);
            g.setColor(color.darker());
            g.drawRoundRect(x, y, 20, 20, 7, 7);
        }

        private void drawCounter(Graphics2D g, int gx, int gy,
                                 int widthCells, int heightCells,
                                 Color color, String label) {
            int x = cellX(gx) + 5;
            int y = cellY(gy) + 5;
            int w = widthCells * CELL - 10;
            int h = heightCells * CELL - 10;
            g.setColor(color);
            g.fillRoundRect(x, y, w, h, 9, 9);
            g.setColor(color.darker());
            g.drawRoundRect(x, y, w, h, 9, 9);
            g.setFont(new Font("SansSerif", Font.BOLD, 9));
            g.setColor(Color.WHITE);
            drawCentered(g, label, x, y, w, h);
        }

        private void drawStove(Graphics2D g, int gx, int gy,
                               int widthCells, int heightCells) {
            int x = cellX(gx) + 5;
            int y = cellY(gy) + 5;
            int w = widthCells * CELL - 10;
            int h = heightCells * CELL - 10;
            g.setColor(new Color(72, 76, 80));
            g.fillRoundRect(x, y, w, h, 9, 9);
            g.setColor(new Color(35, 38, 41));
            for (int row = 0; row < 3; row++) {
                g.drawOval(x + 8, y + 9 + row * 34, 16, 16);
            }
        }

        private void drawShelves(Graphics2D g, int gx, int gy,
                                 int widthCells, int heightCells) {
            int x = cellX(gx) + 6;
            int y = cellY(gy) + 5;
            int w = widthCells * CELL - 12;
            int h = heightCells * CELL - 10;
            g.setColor(new Color(152, 120, 76));
            g.fillRoundRect(x, y, w, h, 7, 7);
            g.setColor(new Color(92, 70, 45));
            for (int row = 1; row < 4; row++) {
                g.drawLine(x + 3, y + row * h / 4, x + w - 3, y + row * h / 4);
            }
        }

        private void drawDoor(Graphics2D g, int px, int py) {
            g.setColor(new Color(185, 132, 72));
            g.fillRoundRect(px + 7, py + 7, CELL - 14, CELL - 14, 8, 8);
            g.setColor(new Color(245, 214, 153));
            g.drawRoundRect(px + 8, py + 8, CELL - 16, CELL - 16, 8, 8);
            g.fillOval(px + CELL - 15, py + CELL / 2, 4, 4);
        }

        private void drawRoomTitle(Graphics2D g, String title,
                                   int gx, int gy, int widthCells, Color color) {
            int x = cellX(gx) + 7;
            int y = cellY(gy) + 7;
            int width = widthCells * CELL - 14;
            g.setFont(roomFont);
            g.setColor(color);
            drawClippedText(g, title, x, y + 13, width);
        }

        private void drawArrow(Graphics2D g, int cx, int cy,
                               boolean horizontal, boolean positive) {
            int length = 12;
            if (horizontal) {
                int x1 = positive ? cx - length / 2 : cx + length / 2;
                int x2 = positive ? cx + length / 2 : cx - length / 2;
                g.drawLine(x1, cy, x2, cy);
                int sign = positive ? 1 : -1;
                g.drawLine(x2, cy, x2 - sign * 4, cy - 3);
                g.drawLine(x2, cy, x2 - sign * 4, cy + 3);
            } else {
                int y1 = positive ? cy - length / 2 : cy + length / 2;
                int y2 = positive ? cy + length / 2 : cy - length / 2;
                g.drawLine(cx, y1, cx, y2);
                int sign = positive ? 1 : -1;
                g.drawLine(cx, y2, cx - 3, y2 - sign * 4);
                g.drawLine(cx, y2, cx + 3, y2 - sign * 4);
            }
        }

        private void drawCardShell(Graphics2D g, int x, int y,
                                   int width, int height, Color color, String title) {
            g.setColor(color);
            g.fillRoundRect(x, y, width, height, 13, 13);
            g.setColor(new Color(0, 0, 0, 25));
            g.drawRoundRect(x, y, width, height, 13, 13);
            g.setFont(labelFont);
            g.setColor(new Color(56, 62, 68));
            g.drawString(title, x + 12, y + 20);
        }

        private void drawLegendEntry(Graphics2D g, int x, int y,
                                     Color color, String code, String description) {
            g.setColor(color);
            g.fillOval(x, y - 10, 14, 14);
            g.setFont(labelFont);
            g.setColor(new Color(54, 60, 67));
            g.drawString(code, x + 22, y + 1);
            g.setFont(bodyFont);
            g.setColor(new Color(93, 99, 106));
            g.drawString(description, x + 94, y + 1);
        }

        private void drawWrappedLine(Graphics2D g, String text,
                                     int x, int baseline, int maxWidth) {
            FontMetrics fm = g.getFontMetrics();
            if (fm.stringWidth(text) <= maxWidth) {
                g.drawString(text, x, baseline);
                return;
            }
            String[] words = text.split(" ");
            StringBuilder line = new StringBuilder();
            int y = baseline;
            for (String word : words) {
                String trial = line.length() == 0 ? word : line + " " + word;
                if (fm.stringWidth(trial) > maxWidth && line.length() > 0) {
                    g.drawString(line.toString(), x, y);
                    y += fm.getHeight();
                    line.setLength(0);
                    line.append(word);
                } else {
                    if (line.length() > 0) line.append(' ');
                    line.append(word);
                }
            }
            if (line.length() > 0) g.drawString(line.toString(), x, y);
        }

        private void drawClippedText(Graphics2D g, String text,
                                     int x, int baseline, int maxWidth) {
            FontMetrics fm = g.getFontMetrics();
            if (fm.stringWidth(text) <= maxWidth) {
                g.drawString(text, x, baseline);
                return;
            }
            String suffix = "…";
            int end = text.length();
            while (end > 0
                    && fm.stringWidth(text.substring(0, end) + suffix) > maxWidth) {
                end--;
            }
            g.drawString(text.substring(0, end) + suffix, x, baseline);
        }

        private void drawCentered(Graphics2D g, String text,
                                  int x, int y, int width, int height) {
            FontMetrics fm = g.getFontMetrics();
            int tx = x + (width - fm.stringWidth(text)) / 2;
            int ty = y + (height - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, tx, ty);
        }

        private int cellX(int x) {
            return GRID_LEFT + x * CELL;
        }

        private int cellY(int y) {
            return GRID_TOP + y * CELL;
        }

        private int sidebarX() {
            return GRID_LEFT + GRID_PIXEL_WIDTH + SIDEBAR_GAP;
        }

        private void enableQuality(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
        }
    }
}
