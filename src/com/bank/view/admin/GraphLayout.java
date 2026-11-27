package com.bank.view.admin;

import com.bank.graph.domain.CustomerGraph;
import com.bank.graph.domain.CustomerNode;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic node placement. No Swing components are used, so it can be unit-tested headlessly.
 *
 * <p>Each connected cluster of customers gets its own cell, so unrelated groups do not tangle.
 * Inside a cell, small clusters sit on a single ring; large clusters use concentric rings with
 * the best-connected customers nearest the centre. Cells are packed left-to-right in rows,
 * largest cluster first.
 */
final class GraphLayout {

    /** Space reserved above the first row for the legend. */
    static final int TOP_PADDING = 36;
    private static final int SINGLE_RING_MAX = 14;
    private static final int MIN_WIDTH = 760;

    record Result(Map<Long, Point2D.Double> positions, int width, int height) { }

    private GraphLayout() { }

    static Result compute(CustomerGraph graph, int nodeRadius) {
        double spacing = 2.0 * nodeRadius + 46;
        double margin = nodeRadius + 42;

        Map<Long, CustomerNode> byId = new HashMap<>();
        graph.nodes().forEach(n -> byId.put(n.id(), n));

        List<Cell> cells = new ArrayList<>();
        for (List<Long> component : graph.connectedComponents()) {
            List<Long> ordered = new ArrayList<>(component);
            ordered.sort(Comparator.<Long>comparingInt(id -> -byId.get(id).connections())
                    .thenComparingLong(id -> id));
            cells.add(arrange(ordered, spacing, margin));
        }
        if (cells.isEmpty()) return new Result(Map.of(), MIN_WIDTH, MIN_WIDTH / 2);

        double totalArea = 0;
        double widest = 0;
        for (Cell c : cells) { totalArea += c.size * c.size; widest = Math.max(widest, c.size); }
        double targetWidth = Math.max(Math.max(widest, Math.ceil(Math.sqrt(totalArea) * 1.2)), MIN_WIDTH);

        Map<Long, Point2D.Double> positions = new HashMap<>();
        double x = 0, y = TOP_PADDING, rowHeight = 0, usedWidth = 0;
        for (Cell c : cells) {
            if (x > 0 && x + c.size > targetWidth) {
                y += rowHeight;
                x = 0;
                rowHeight = 0;
            }
            double cx = x + c.size / 2.0;
            double cy = y + c.size / 2.0;
            for (Map.Entry<Long, Point2D.Double> e : c.local.entrySet()) {
                positions.put(e.getKey(), new Point2D.Double(cx + e.getValue().x, cy + e.getValue().y));
            }
            x += c.size;
            usedWidth = Math.max(usedWidth, x);
            rowHeight = Math.max(rowHeight, c.size);
        }
        return new Result(positions, (int) Math.ceil(Math.max(usedWidth, MIN_WIDTH)),
                (int) Math.ceil(y + rowHeight));
    }

    private static Cell arrange(List<Long> ordered, double spacing, double margin) {
        Map<Long, Point2D.Double> local = new HashMap<>();
        int k = ordered.size();
        double radius;
        if (k == 1) {
            local.put(ordered.get(0), new Point2D.Double(0, 0));
            radius = 0;
        } else if (k == 2) {
            radius = spacing * 0.9;
            local.put(ordered.get(0), new Point2D.Double(-radius, 0));
            local.put(ordered.get(1), new Point2D.Double(radius, 0));
        } else if (k <= SINGLE_RING_MAX) {
            radius = Math.max(spacing, spacing / (2.0 * Math.sin(Math.PI / k)));
            for (int i = 0; i < k; i++) {
                double angle = -Math.PI / 2.0 + 2.0 * Math.PI * i / k;
                local.put(ordered.get(i), new Point2D.Double(Math.cos(angle) * radius, Math.sin(angle) * radius));
            }
        } else {
            local.put(ordered.get(0), new Point2D.Double(0, 0));    // best-connected customer at the centre
            int placed = 1;
            int ring = 1;
            radius = 0;
            while (placed < k) {
                radius = ring * spacing * 1.1;
                int capacity = Math.max(6, (int) Math.floor(2.0 * Math.PI * radius / spacing));
                int inRing = Math.min(capacity, k - placed);
                for (int i = 0; i < inRing; i++) {
                    double angle = -Math.PI / 2.0 + 2.0 * Math.PI * i / inRing;
                    local.put(ordered.get(placed + i),
                            new Point2D.Double(Math.cos(angle) * radius, Math.sin(angle) * radius));
                }
                placed += inRing;
                ring++;
            }
        }
        return new Cell(local, 2.0 * (radius + margin));
    }

    private record Cell(Map<Long, Point2D.Double> local, double size) { }
}
