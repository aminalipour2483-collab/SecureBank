package com.bank.view.admin;

import com.bank.graph.domain.CustomerGraph;
import com.bank.graph.domain.CustomerNode;
import com.bank.graph.domain.RelationshipEdge;
import com.bank.graph.domain.RelationshipType;
import com.bank.view.DateUtil;
import com.bank.view.UITheme;

import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Paints a {@link CustomerGraph}. Holds no business logic: layout comes from {@link GraphLayout} and
 * is computed once per graph, not on every repaint.
 */
final class CustomerGraphCanvas extends JPanel {

    interface NodeClickListener {
        void nodeClicked(long customerId, boolean additive);
    }

    /** Edge labels are drawn for every edge up to this many; beyond it, only for selected customers. */
    private static final int LABEL_ALL_THRESHOLD = 120;
    private static final Color TRANSFER_COLOR = new Color(0x73, 0x89, 0xA4);
    private static final Color PHONE_COLOR = new Color(0x8E, 0x44, 0xAD);

    private record EdgeKey(long from, long to) { }

    private final NodeClickListener listener;
    private CustomerGraph graph;
    private Map<Long, Point2D.Double> positions = Map.of();
    private Set<EdgeKey> transferKeys = Set.of();
    private Set<Long> selected = Set.of();
    private int nodeRadius = 30;

    CustomerGraphCanvas(NodeClickListener listener) {
        this.listener = listener;
        setOpaque(true);
        setBackground(UITheme.CARD);
        setPreferredSize(new Dimension(900, 640));
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                Long id = nodeAt(e.getPoint());
                if (id != null) listener.nodeClicked(id, e.isControlDown() || e.isShiftDown());
            }
        });
    }

    void setGraph(CustomerGraph graph) {
        this.graph = graph;
        this.selected = Set.of();
        int n = graph == null ? 0 : graph.nodes().size();
        nodeRadius = n > 150 ? 15 : n > 60 ? 19 : n > 30 ? 24 : 30;
        if (graph == null || graph.isEmpty()) {
            positions = Map.of();
            transferKeys = Set.of();
            setPreferredSize(new Dimension(900, 640));
        } else {
            GraphLayout.Result layout = GraphLayout.compute(graph, nodeRadius);
            positions = layout.positions();
            Set<EdgeKey> keys = new HashSet<>();
            for (RelationshipEdge e : graph.edges()) {
                if (e.type() == RelationshipType.MONEY_TRANSFER) keys.add(new EdgeKey(e.fromId(), e.toId()));
            }
            transferKeys = keys;
            setPreferredSize(new Dimension(layout.width(), layout.height()));
        }
        revalidate();
        repaint();
    }

    void setSelection(Set<Long> ids) {
        this.selected = Set.copyOf(ids);
        repaint();
    }

    private Long nodeAt(java.awt.Point p) {
        Long best = null;
        double bestDistance = nodeRadius + 8;
        for (Map.Entry<Long, Point2D.Double> e : positions.entrySet()) {
            double d = e.getValue().distance(p);
            if (d <= bestDistance) { bestDistance = d; best = e.getKey(); }
        }
        return best;
    }

    @Override public String getToolTipText(MouseEvent event) {
        Long id = nodeAt(event.getPoint());
        if (id == null || graph == null) return null;
        return graph.node(id).map(n -> "<html><b>" + escape(n.displayName()) + "</b> (#" + n.id() + ")<br>"
                + "Sent " + n.transfersSent() + " · Received " + n.transfersReceived()
                + "<br>Connected to " + n.connections() + " customer(s)"
                + (n.banned() ? "<br><font color='#DC2626'>BANNED</font>" : "") + "</html>").orElse(null);
    }

    // ------------------------------------------------------------------------------------------
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (graph == null || graph.isEmpty()) {
                g.setColor(UITheme.TEXT_SECONDARY);
                g.setFont(UITheme.FONT_BODY);
                String message = graph == null ? "Click “Generate Graph” to load customer relationships"
                        : "No relationships found for the selected options";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(message, Math.max(20, (getWidth() - fm.stringWidth(message)) / 2), getHeight() / 2);
                return;
            }
            boolean labelAll = graph.edges().size() <= LABEL_ALL_THRESHOLD;
            for (RelationshipEdge edge : graph.edges()) drawEdge(g, edge, labelAll);
            for (CustomerNode node : graph.nodes()) drawNode(g, node);
            g.setFont(new Font(UITheme.FONT_FAMILY, Font.PLAIN, 11));
            g.setColor(UITheme.TEXT_SECONDARY);
            g.drawString("Solid arrow: money sender → receiver   ·   Dashed: shared phone   ·   Red: banned"
                    + "   ·   Click to select, Ctrl/Shift-click for several", 14, 20);
        } finally {
            g.dispose();
        }
    }

    private void drawEdge(Graphics2D g, RelationshipEdge edge, boolean labelAll) {
        Point2D.Double from = positions.get(edge.fromId());
        Point2D.Double to = positions.get(edge.toId());
        if (from == null || to == null) return;

        boolean touchesSelection = !selected.isEmpty() && edge.involvesAny(selected);
        boolean dimmed = !selected.isEmpty() && !touchesSelection;
        boolean transfer = edge.type() == RelationshipType.MONEY_TRANSFER;
        Color base = transfer ? TRANSFER_COLOR : PHONE_COLOR;
        int alpha = dimmed ? 80 : touchesSelection ? 255 : 175;
        Color color = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);

        double dx = to.x - from.x, dy = to.y - from.y;
        double distance = Math.max(1, Math.hypot(dx, dy));
        double bend = 0;
        if (transfer && transferKeys.contains(new EdgeKey(edge.toId(), edge.fromId()))) {
            bend = 30.0;   // the perpendicular flips with direction, so A→B and B→A curve to opposite sides
        }
        double ctrlX = (from.x + to.x) / 2.0 + (-dy / distance) * bend;
        double ctrlY = (from.y + to.y) / 2.0 + (dx / distance) * bend;
        Point2D.Double ctrl = new Point2D.Double(ctrlX, ctrlY);

        Path2D path = new Path2D.Double();
        path.moveTo(from.x, from.y);
        path.quadTo(ctrlX, ctrlY, to.x, to.y);
        float width = transfer ? (float) Math.min(5.0, 1.4 + Math.log1p(edge.transferCount())) : 1.6f;
        if (touchesSelection) width += 1.0f;
        g.setColor(color);
        g.setStroke(transfer
                ? new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                : new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{7f, 5f}, 0f));
        g.draw(path);

        if (transfer) {
            double t = Math.max(0.70, 1.0 - (nodeRadius + 10.0) / distance);
            Point2D.Double tip = quad(from, ctrl, to, t);
            Point2D.Double tan = quadTangent(from, ctrl, to, t);
            double len = Math.max(1, Math.hypot(tan.x, tan.y));
            double ux = tan.x / len, uy = tan.y / len;
            double arrowLength = 11, arrowHalfWidth = 5.5;
            Path2D arrow = new Path2D.Double();
            arrow.moveTo(tip.x, tip.y);
            arrow.lineTo(tip.x - ux * arrowLength + uy * arrowHalfWidth, tip.y - uy * arrowLength - ux * arrowHalfWidth);
            arrow.moveTo(tip.x, tip.y);
            arrow.lineTo(tip.x - ux * arrowLength - uy * arrowHalfWidth, tip.y - uy * arrowLength + ux * arrowHalfWidth);
            g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(arrow);
        }

        if ((labelAll || touchesSelection) && !dimmed) {
            String label = transfer
                    ? edge.transferCount() + " tx · " + DateUtil.formatAmount(orZero(edge.totalAmount()))
                    : edge.evidence().replace("Shared ", "");
            Point2D.Double mid = quad(from, ctrl, to, 0.5);
            g.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 10));
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(label) + 12, h = fm.getHeight() + 4;
            int lx = (int) Math.round(mid.x - w / 2.0), ly = (int) Math.round(mid.y - h / 2.0);
            g.setColor(new Color(255, 255, 255, 230));
            g.fillRoundRect(lx, ly, w, h, 10, 10);
            g.setColor(base.darker());
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(lx, ly, w, h, 10, 10);
            g.drawString(label, lx + 6, ly + fm.getAscent() + 1);
        }
    }

    private void drawNode(Graphics2D g, CustomerNode node) {
        Point2D.Double p = positions.get(node.id());
        if (p == null) return;
        int x = (int) Math.round(p.x - nodeRadius), y = (int) Math.round(p.y - nodeRadius);
        boolean isSelected = selected.contains(node.id());
        g.setColor(node.banned() ? new Color(0xF9, 0xDD, 0xDA) : new Color(0xDF, 0xEC, 0xF8));
        g.fillOval(x, y, nodeRadius * 2, nodeRadius * 2);
        g.setStroke(new BasicStroke(isSelected ? 4.0f : 2.0f));
        g.setColor(isSelected ? UITheme.WARNING : node.banned() ? UITheme.DANGER : UITheme.PRIMARY);
        g.drawOval(x, y, nodeRadius * 2, nodeRadius * 2);

        g.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, nodeRadius > 22 ? 11 : 9));
        g.setColor(UITheme.TEXT_PRIMARY);
        FontMetrics fm = g.getFontMetrics();
        String idText = "#" + node.id();
        g.drawString(idText, (int) (p.x - fm.stringWidth(idText) / 2.0), (int) p.y + fm.getAscent() / 2 - 2);

        if (nodeRadius >= 19 || isSelected) {                 // names are dropped on very dense graphs
            g.setFont(new Font(UITheme.FONT_FAMILY, Font.PLAIN, 11));
            fm = g.getFontMetrics();
            String name = abbreviate(node.displayName(), 19);
            g.setColor(UITheme.TEXT_PRIMARY);
            g.drawString(name, (int) (p.x - fm.stringWidth(name) / 2.0), y + nodeRadius * 2 + 14);
        }
    }

    private static BigDecimal orZero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static Point2D.Double quad(Point2D.Double a, Point2D.Double c, Point2D.Double b, double t) {
        double u = 1.0 - t;
        return new Point2D.Double(u * u * a.x + 2 * u * t * c.x + t * t * b.x,
                u * u * a.y + 2 * u * t * c.y + t * t * b.y);
    }

    private static Point2D.Double quadTangent(Point2D.Double a, Point2D.Double c, Point2D.Double b, double t) {
        return new Point2D.Double(2 * (1 - t) * (c.x - a.x) + 2 * t * (b.x - c.x),
                2 * (1 - t) * (c.y - a.y) + 2 * t * (b.y - c.y));
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
