package com.bank.view.admin;

import com.bank.graph.application.CustomerGraphService;
import com.bank.graph.domain.*;
import com.bank.security.AdminPrincipal;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.bank.testkit.TestKit.*;

/** Headless checks of layout invariants, plus rendering of sample graphs (PNG path via args[0] directory). */
public class GraphLayoutAndCanvasTest {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 12, 0);
    static final GraphCriteria ALL = new GraphCriteria(null, true, 1000);

    static CustomerIdentity c(long id, String name, boolean banned) { return new CustomerIdentity(id, name, banned); }
    static TransferFlow f(CustomerIdentity a, CustomerIdentity b, long n, String amt) {
        return new TransferFlow(a, b, n, new BigDecimal(amt), T0);
    }

    static CustomerGraph smallGraph() {
        CustomerIdentity alice = c(1, "Alice Johnson", false), bob = c(2, "Bob Martinez", false),
                carol = c(3, "Carol Smith", false), dan = c(4, "Dan Walker", true),
                eve = c(5, "Eve Adams", false), frank = c(6, "Frank Moore", false), gina = c(7, "Gina Ross", false);
        return CustomerGraphBuilder.build(new GraphSourceData(List.of(
                f(alice, bob, 3, "300.00"), f(bob, alice, 1, "50.00"), f(bob, carol, 2, "150.00"),
                f(carol, dan, 4, "9800.00"), f(dan, alice, 1, "12000.00"), f(eve, frank, 2, "75.00")),
                false, List.of(new SharedPhoneMember("+12025550999", gina), new SharedPhoneMember("+12025550999", eve)),
                false), ALL, T0);
    }

    static CustomerGraph bigGraph(int clusters, int perCluster) {
        Random rnd = new Random(42);
        List<TransferFlow> flows = new ArrayList<>();
        long id = 1;
        for (int cl = 0; cl < clusters; cl++) {
            int size = Math.max(2, perCluster - cl * 2);
            long base = id;
            for (int i = 0; i < size; i++) {
                CustomerIdentity a = c(base + i, "Customer " + (base + i), rnd.nextInt(25) == 0);
                CustomerIdentity b = c(base + (i + 1) % size, "Customer " + (base + (i + 1) % size), false);
                if (a.id() != b.id()) flows.add(f(a, b, 1 + rnd.nextInt(5), String.valueOf(10 + rnd.nextInt(5000)) + ".00"));
                if (i % 3 == 0 && size > 4) {
                    CustomerIdentity hub = c(base, "Customer " + base, false);
                    if (hub.id() != a.id()) flows.add(f(hub, a, 2, "100.00"));
                }
            }
            id += size;
        }
        return CustomerGraphBuilder.build(new GraphSourceData(flows, false, List.of(), false), ALL, T0);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        layoutInvariants();
        canvasRenders(args.length > 0 ? args[0] : null);
        finish("GraphLayoutAndCanvasTest");
    }

    static void layoutInvariants() {
        section("[1] GraphLayout");
        for (CustomerGraph g : List.of(smallGraph(), bigGraph(6, 30))) {
            int r = 24;
            GraphLayout.Result a = GraphLayout.compute(g, r);
            GraphLayout.Result b = GraphLayout.compute(g, r);
            String tag = g.nodes().size() + " nodes: ";
            checkEquals(tag + "every node placed", g.nodes().size(), a.positions().size());
            check(tag + "deterministic (same input → same output)", a.positions().equals(b.positions())
                    && a.width() == b.width() && a.height() == b.height());
            boolean inside = a.positions().values().stream().allMatch(p ->
                    p.x >= r && p.y >= r && p.x <= a.width() - r && p.y <= a.height() - r);
            check(tag + "all nodes inside the reported canvas", inside);
            double minDist = Double.MAX_VALUE;
            List<Point2D.Double> pts = new ArrayList<>(a.positions().values());
            for (int i = 0; i < pts.size(); i++)
                for (int j = i + 1; j < pts.size(); j++) minDist = Math.min(minDist, pts.get(i).distance(pts.get(j)));
            check(tag + "no two nodes overlap (min distance " + Math.round(minDist) + "px ≥ 2×radius)", minDist >= 2 * r);
        }
        check("empty graph yields a usable minimum size",
                GraphLayout.compute(CustomerGraphBuilder.build(GraphSourceData.empty(), ALL, T0), 30).width() > 0);

        // Clusters must not interleave: bounding boxes of different components never overlap.
        CustomerGraph g = bigGraph(5, 20);
        GraphLayout.Result res = GraphLayout.compute(g, 20);
        List<double[]> boxes = new ArrayList<>();
        for (List<Long> comp : g.connectedComponents()) {
            double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
            for (long id : comp) { Point2D.Double p = res.positions().get(id);
                x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); }
            boxes.add(new double[]{x0, y0, x1, y1});
        }
        boolean disjoint = true;
        for (int i = 0; i < boxes.size(); i++) for (int j = i + 1; j < boxes.size(); j++) {
            double[] p = boxes.get(i), q = boxes.get(j);
            if (p[0] < q[2] && q[0] < p[2] && p[1] < q[3] && q[1] < p[3]) disjoint = false;
        }
        check("separate clusters occupy disjoint regions", disjoint);
        long hubId = g.connectedComponents().get(0).stream()
                .max(Comparator.comparingInt(id -> g.node(id).orElseThrow().connections())).orElseThrow();
        Point2D.Double hub = res.positions().get(hubId);
        double meanDist = 0; int n = 0;
        List<Long> comp0 = g.connectedComponents().get(0);
        for (long id : comp0) { if (id != hubId) { meanDist += hub.distance(res.positions().get(id)); n++; } }
        check("largest cluster's best-connected customer sits at its centre (mean distance to others ≈ ring radius)",
                n > 0 && hub.distance(centroid(comp0, res)) < 5);
    }

    static Point2D.Double centroid(List<Long> ids, GraphLayout.Result r) {
        double x = 0, y = 0;
        for (long id : ids) { x += r.positions().get(id).x; y += r.positions().get(id).y; }
        return new Point2D.Double(x / ids.size(), y / ids.size());
    }

    static void canvasRenders(String outDir) throws Exception {
        section("[2] CustomerGraphCanvas (headless render)");
        List<Long> clicks = new ArrayList<>();
        CustomerGraphCanvas canvas = new CustomerGraphCanvas((id, additive) -> clicks.add(id));
        BufferedImage empty = paint(canvas, 700, 400);
        check("empty/initial canvas paints without error", empty != null);

        CustomerGraph small = smallGraph();
        canvas.setGraph(small);
        Dimension d = canvas.getPreferredSize();
        BufferedImage img = paint(canvas, d.width, d.height);
        check("small graph paints; image is not blank", distinctColors(img) > 8);
        canvas.setSelection(Set.of(2L));
        BufferedImage img2 = paint(canvas, d.width, d.height);
        check("selection changes the rendering", !sameImage(img, img2));
        save(outDir, "small_graph.png", img2);

        CustomerGraph big = bigGraph(8, 40);
        canvas.setGraph(big);
        Dimension bd = canvas.getPreferredSize();
        long t0 = System.nanoTime();
        BufferedImage bigImg = paint(canvas, bd.width, bd.height);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("large graph (" + big.nodes().size() + " nodes, " + big.edges().size() + " edges) paints in " + ms + " ms",
                bigImg != null && ms < 5000);
        save(outDir, "large_graph.png", bigImg);

        canvas.setGraph(small);
        canvas.setSize(canvas.getPreferredSize());
        canvas.setSelection(Set.of());
        // Hit-testing through the real mouse listener: click exactly on a node.
        Point2D.Double p = GraphLayout.compute(small, 30).positions().get(3L);
        java.awt.event.MouseEvent click = new java.awt.event.MouseEvent(canvas, java.awt.event.MouseEvent.MOUSE_CLICKED,
                0, 0, (int) p.x, (int) p.y, 1, false);
        for (var l : canvas.getMouseListeners()) l.mouseClicked(click);
        checkEquals("clicking a node notifies the listener with its customer id", List.of(3L), clicks);
        java.awt.event.MouseEvent miss = new java.awt.event.MouseEvent(canvas, java.awt.event.MouseEvent.MOUSE_CLICKED,
                0, 0, 1, 1, 1, false);
        for (var l : canvas.getMouseListeners()) l.mouseClicked(miss);
        checkEquals("clicking empty space selects nothing", 1, clicks.size());
        String tip = canvas.getToolTipText(new java.awt.event.MouseEvent(canvas, java.awt.event.MouseEvent.MOUSE_MOVED,
                0, 0, (int) p.x, (int) p.y, 0, false));
        check("tooltip describes the hovered customer", tip != null && tip.contains("Carol Smith"));
    }

    static BufferedImage paint(CustomerGraphCanvas canvas, int w, int h) {
        canvas.setSize(w, h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        canvas.paint(g);
        g.dispose();
        return img;
    }

    static int distinctColors(BufferedImage img) {
        Set<Integer> s = new HashSet<>();
        for (int y = 0; y < img.getHeight(); y += 3) for (int x = 0; x < img.getWidth(); x += 3) s.add(img.getRGB(x, y));
        return s.size();
    }

    static boolean sameImage(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++)
            if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
        return true;
    }

    static void save(String dir, String name, BufferedImage img) throws Exception {
        if (dir != null) ImageIO.write(img, "png", new File(dir, name));
    }
}
