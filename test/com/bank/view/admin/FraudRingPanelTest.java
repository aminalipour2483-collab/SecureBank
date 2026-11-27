package com.bank.view.admin;

import com.bank.fraudring.application.FraudRingService;
import com.bank.fraudring.domain.FraudRingAnalysis;
import com.bank.fraudring.domain.FraudRingCriteria;
import com.bank.security.AdminPrincipal;
import com.bank.security.ConfiguredAdminAuthenticationService;
import com.bank.util.BCryptUtil;

import javax.swing.*;
import javax.swing.table.TableModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bank.testkit.TestKit.*;

/**
 * Phase 4 UI tests: drives the real FraudRingPanel headlessly against a fake
 * FraudRingService (no database, no real Swing windows). Same style as
 * CustomerGraphPanelTest: find() + layoutDeep() + invokeAndWait.
 *
 * <p>Verified here: criteria form → service call, the payload → table
 * mapping (ranked rows, band labels, signal codes), band tints, the XAI
 * reason dialog (Persian text with weights, verbatim from the payload),
 * the empty-payload path, and the error path (service failure → status
 * message, no crash).
 */
public final class FraudRingPanelTest {

    static final String CUSTOMERS_JSON = "{\"phase\":3,\"customersScored\":2,"
            + "\"bandCounts\":{\"LOW\":0,\"MEDIUM\":1,\"HIGH\":1,\"CRITICAL\":0},"
            + "\"customers\":["
            + "{\"customerId\":9,\"displayName\":\"Zed Quill\",\"score\":55,\"band\":\"HIGH\","
            + "\"persianBand\":\"زیاد\",\"communityId\":0,\"cycleCount\":1,\"degree\":4,"
            + "\"betweenness\":0.5,\"pageRank\":0.06,\"reasons\":["
            + "{\"code\":\"MONEY_CYCLE\",\"title\":\"حلقهٔ چرخش پول\",\"points\":35,"
            + "\"detail\":\"در ۱ حلقهٔ انتقال پول شرکت دارد؛ مجموع گردش این حلقه‌ها 1200.00 است.\"},"
            + "{\"code\":\"SHARED_PHONE\",\"title\":\"شمارهٔ موبایل مشترک\",\"points\":20,"
            + "\"detail\":\"شمارهٔ تلفن نرمال‌شدهٔ این مشتری با ۲ مشتری دیگر یکسان است.\"}]},"
            + "{\"customerId\":4,\"displayName\":\"Ann Doe\",\"score\":10,\"band\":\"LOW\","
            + "\"persianBand\":\"کم\",\"communityId\":1,\"cycleCount\":0,\"degree\":1,"
            + "\"betweenness\":0.0,\"pageRank\":0.02,\"reasons\":["
            + "{\"code\":\"VELOCITY\",\"title\":\"انفجار تراکنش\",\"points\":10,"
            + "\"detail\":\"در یک جفت مشتری مشخص ۱۰ انتقال کامل‌شده ثبت شده است.\"}]}]}";

    static final String GROUPS_JSON = "{\"phase\":3,\"groupsScored\":1,"
            + "\"bandCounts\":{\"LOW\":0,\"MEDIUM\":1,\"HIGH\":0,\"CRITICAL\":0},"
            + "\"groups\":[{\"communityId\":0,\"size\":3,\"members\":[9,4,7],\"score\":55,"
            + "\"band\":\"HIGH\",\"persianBand\":\"زیاد\",\"density\":1.0,"
            + "\"internalVolume\":\"1200.00\",\"reasons\":["
            + "{\"code\":\"GROUP_CYCLE\",\"title\":\"حلقهٔ کامل پول داخل گروه\",\"points\":40,"
            + "\"detail\":\"این گروه ۱ حلقهٔ چرخش پول کاملاً داخلی دارد.\"},"
            + "{\"code\":\"GROUP_DENSITY\",\"title\":\"توپولوژی متراکم\",\"points\":15,"
            + "\"detail\":\"چگالی ارتباطات داخلی گروه ۱.۰۰۰۰ است.\"}]}]}";

    static final String STATS_JSON = "{\"phase\":3,\"nodeCount\":3,\"edgeCount\":3,"
            + "\"communityCount\":1,\"cycleCount\":1,\"truncated\":false,\"scope\":\"FULL\"}";

    /** Fake service: records criteria, returns a canned analysis or throws. */
    static class FakeService implements FraudRingService {
        final List<FraudRingCriteria> criteriaSeen = new CopyOnWriteArrayList<>();
        final List<AdminPrincipal> principals = new CopyOnWriteArrayList<>();
        final AtomicInteger runs = new AtomicInteger();
        volatile boolean failNext;
        FraudRingAnalysis next = new FraudRingAnalysis(7L, "ab12",
                com.bank.fraudring.domain.FraudRingScope.FULL, null, null,
                STATS_JSON, CUSTOMERS_JSON, GROUPS_JSON, "root",
                java.time.LocalDateTime.of(2026, 11, 17, 10, 0));

        @Override
        public FraudRingAnalysis analyze(AdminPrincipal admin, FraudRingCriteria criteria) {
            principals.add(admin);
            criteriaSeen.add(criteria);
            runs.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new com.bank.exception.BankException("database unreachable");
            }
            return next;
        }

        @Override
        public Optional<FraudRingAnalysis> findAnalysis(AdminPrincipal admin, String idempotencyKey) {
            return Optional.of(next);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        AdminPrincipal admin = new ConfiguredAdminAuthenticationService(
                "root", BCryptUtil.hash("pw"), Clock.systemUTC())
                .authenticate("root", "pw").orElseThrow();

        section("[1] Construction");
        checkThrows("panel refuses to be built without an administrator",
                NullPointerException.class, () -> new FraudRingPanel(null, new FakeService()));
        checkThrows("panel refuses to be built without a service",
                NullPointerException.class, () -> new FraudRingPanel(admin, null));

        FakeService stub = new FakeService();
        FraudRingPanel[] holder = new FraudRingPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new FraudRingPanel(admin, stub));
        FraudRingPanel panel = holder[0];
        SwingUtilities.invokeAndWait(() -> { panel.setSize(1300, 760); layoutDeep(panel); });

        check("no service call happens until Analyze is clicked", stub.criteriaSeen.isEmpty());
        check("tables start empty", panel.customersTable().getRowCount() == 0
                && panel.groupsTable().getRowCount() == 0);
        check("band colours exist for every band",
                java.util.Arrays.stream(com.bank.fraudring.domain.RiskBand.values())
                        .allMatch(b -> FraudRingPanel.bandTint(b) != null));

        section("[2] Analyze run → tables fill from the payload");
        SwingUtilities.invokeAndWait(() -> panel.fireAnalyze());
        waitFor(() -> !stub.criteriaSeen.isEmpty(), "the service was called");
        waitFor(() -> panel.customersTable().getRowCount() > 0, "the customer table filled");
        waitFor(() -> panel.status().getText().contains("Analysis #7"), "the status line updated");

        checkEquals("one run recorded", 1, stub.criteriaSeen.size());
        check("the admin principal was passed through", stub.principals.get(0) == admin);
        FraudRingCriteria criteria = stub.criteriaSeen.get(0);
        check("default run is FULL scope", criteria.scope() == com.bank.fraudring.domain.FraudRingScope.FULL);
        check("default period is all-time", criteria.lookbackDays() == null);
        check("shared phones included by default", criteria.includeSharedPhone());

        JTable customers = panel.customersTable();
        checkEquals("two customers scored", 2, customers.getRowCount());
        TableModel model = customers.getModel();
        checkEquals("columns expose the XAI surface", List.of("Rank", "Customer", "Score", "Band",
                        "Community", "Cycles", "Degree", "Signals"),
                java.util.Arrays.stream(columnNames(model)).toList());
        checkEquals("top row is the highest scorer", 9L,
                ((String) model.getValueAt(0, 1)).contains("#9") ? 9L : -1L);
        checkEquals("top row score", 55, model.getValueAt(0, 2));
        checkEquals("top row band is Persian", "زیاد", model.getValueAt(0, 3));
        checkEquals("top row carries both signal codes", "MONEY_CYCLE+SHARED_PHONE",
                model.getValueAt(0, 7));
        checkEquals("second row is the low scorer", 10, model.getValueAt(1, 2));
        check("status line names the analysis id", panel.status().getText().contains("Analysis #7"));
        check("status line carries the node/cycle summary",
                panel.status().getText().contains("3 customers"));
        check("band summary counts in Persian",
                panel.summary().getText().contains("زیاد 1"));

        JTable groups = panel.groupsTable();
        checkEquals("one group scored", 1, groups.getRowCount());
        checkEquals("group score", 55, groups.getModel().getValueAt(0, 2));
        checkEquals("group size", 3, groups.getModel().getValueAt(0, 4));
        check("group members listed", ((String) groups.getModel().getValueAt(0, 5)).contains("9"));
        checkEquals("group signals", "GROUP_CYCLE+GROUP_DENSITY", groups.getModel().getValueAt(0, 6));

        section("[3] band tints colour the rows");
        Component cell = customers.prepareRenderer(
                customers.getCellRenderer(0, 2), 0, 2);
        check("HIGH row tinted amber", ((Color) cell.getBackground()).getGreen() > 200
                && ((Color) cell.getBackground()).getRed() > 240);
        Component lowCell = customers.prepareRenderer(
                customers.getCellRenderer(1, 2), 1, 2);
        check("LOW row stays card-white", Color.WHITE.equals(lowCell.getBackground())
                || FraudRingPanel.bandTint(com.bank.fraudring.domain.RiskBand.LOW)
                        .equals(lowCell.getBackground()));

        section("[4] XAI reason dialog content");
        SwingUtilities.invokeAndWait(() -> {
            customers.setRowSelectionInterval(0, 0);
            panel.showReasonsDialogForTest(0);
        });
        JPanel dialogContent = panel.lastDialogContentForTest();
        check("reason content built", dialogContent != null);
        JTable reasons = find(dialogContent, JTable.class, t -> t.getColumnName(2) != null
                && t.getColumnName(2).startsWith("Why"));
        check("reason table present", reasons != null);
        if (reasons != null) {
            checkEquals("two reasons for the top customer", 2, reasons.getRowCount());
            checkEquals("first reason is the heaviest", "MONEY_CYCLE",
                    reasons.getModel().getValueAt(0, 0));
            checkEquals("weight shown", 35, reasons.getModel().getValueAt(0, 1));
            check("Persian detail verbatim from the payload",
                    String.valueOf(reasons.getModel().getValueAt(0, 2))
                            .contains("در ۱ حلقهٔ انتقال پول شرکت دارد"));
            check("dialog footer explains the score",
                    find(dialogContent, JLabel.class, l -> l.getText().contains("sum of the weights")) != null);
        }

        section("[5] group reason dialog content");
        SwingUtilities.invokeAndWait(() -> {
            groups.setRowSelectionInterval(0, 0);
            panel.showGroupReasonsDialogForTest(0);
        });
        JPanel groupContent = panel.lastDialogContentForTest();
        check("group reason content built", groupContent != null);
        if (groupContent != null) {
            JTable groupReasons = find(groupContent, JTable.class, t -> t.getColumnName(2) != null
                    && t.getColumnName(2).startsWith("Why"));
            checkEquals("two group reasons", 2,
                    groupReasons == null ? -1 : groupReasons.getRowCount());
            check("Persian group reason verbatim",
                    groupReasons != null && String.valueOf(
                            groupReasons.getModel().getValueAt(0, 2)).contains("حلقهٔ چرخش پول"));
        }

        section("[6] criteria form → service criteria");
        SwingUtilities.invokeAndWait(() -> {
            panel.singleScope().setSelected(true);
            panel.scopeId().setValue(42L);
            panel.lookback().setSelectedIndex(1);          // Last 30 days
            panel.sharedPhone().setSelected(false);
        });
        SwingUtilities.invokeAndWait(() -> panel.fireAnalyze());
        waitFor(() -> stub.criteriaSeen.size() == 2 && panel.analyze().isEnabled(), "the second run completed");
        FraudRingCriteria single = stub.criteriaSeen.get(1);
        check("SINGLE scope passed", single.scope() == com.bank.fraudring.domain.FraudRingScope.SINGLE);
        checkEquals("scope customer passed", 42L,
                single.customerIds().iterator().next());
        checkEquals("lookback passed", 30, single.lookbackDays().intValue());
        check("shared phone flag honoured", !single.includeSharedPhone());

        section("[7] empty payload stays usable");
        FakeService empty = new FakeService();
        empty.next = new FraudRingAnalysis(8L, "zz",
                com.bank.fraudring.domain.FraudRingScope.FULL, null, null,
                STATS_JSON, "{\"phase\":3,\"customersScored\":0,\"customers\":[]}",
                "{\"phase\":3,\"groupsScored\":0,\"groups\":[]}", "root",
                java.time.LocalDateTime.of(2026, 11, 17, 11, 0));
        FraudRingPanel[] emptyHolder = new FraudRingPanel[1];
        SwingUtilities.invokeAndWait(() -> emptyHolder[0] = new FraudRingPanel(admin, empty));
        FraudRingPanel emptyPanel = emptyHolder[0];
        SwingUtilities.invokeAndWait(() -> emptyPanel.fireAnalyze());
        waitFor(() -> emptyPanel.status().getText().contains("No transfer activity"), "empty notice shown");
        checkEquals("empty customer table", 0, emptyPanel.customersTable().getRowCount());
        checkEquals("empty group table", 0, emptyPanel.groupsTable().getRowCount());
        check("empty summary is clean", emptyPanel.summary().getText().contains("No customers"));
        check("empty analysis id still recorded", emptyPanel.currentAnalysis().analysisId() == 8L);

        section("[8] service failure → status message, no crash");
        stub.failNext = true;
        SwingUtilities.invokeAndWait(() -> panel.fireAnalyze());
        waitFor(() -> panel.status().getText().contains("Failed to run the fraud-ring analysis"),
                "failure surfaced");
        check("failure status set", panel.status().getText().contains("Failed to run"));
        check("table keeps the previous payload after a failure",
                panel.customersTable().getRowCount() == 2);
        check("button re-enabled after a failure", panel.analyze().isEnabled());

        section("[9] payload scalpers");
        List<FraudRingPanel.CustomerRow> parsed = FraudRingPanel.parseCustomers(CUSTOMERS_JSON);
        checkEquals("parser read both customers", 2, parsed.size());
        checkEquals("parser kept the score order", 55, parsed.get(0).score());
        checkEquals("parser read the band", com.bank.fraudring.domain.RiskBand.HIGH, parsed.get(0).band());
        checkEquals("parser read cycle count", 1, parsed.get(0).cycleCount());
        checkEquals("parser read reasons", 2, parsed.get(0).reasons().size());
        check("parser kept Persian reason text",
                parsed.get(0).reasons().get(0).detail().contains("حلقهٔ"));
        List<FraudRingPanel.GroupRow> groupsParsed = FraudRingPanel.parseGroups(GROUPS_JSON);
        checkEquals("parser read the group", 1, groupsParsed.size());
        checkEquals("parser read members", List.of(9L, 4L, 7L), groupsParsed.get(0).memberIds());
        checkEquals("parser read internal volume", "1200.00", groupsParsed.get(0).internalVolume());
        checkThrows("missing field is loud", IllegalArgumentException.class,
                () -> FraudRingPanel.parseCustomers(
                        "{\"customers\":[{\"customerId\":9,\"displayName\":\"x\"}]}"));

        finish("FraudRingPanelTest");
    }

    static String[] columnNames(TableModel model) {
        String[] names = new String[model.getColumnCount()];
        for (int i = 0; i < names.length; i++) names[i] = model.getColumnName(i);
        return names;
    }

    // ── helpers copied from CustomerGraphPanelTest (same harness) ──

    static void layoutDeep(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) if (child instanceof Container cc) layoutDeep(cc);
    }

    static <T extends Component> T find(Container root, Class<T> type, java.util.function.Predicate<T> pred) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) {
                @SuppressWarnings("unchecked") T t = (T) c;
                if (pred.test(t)) return t;
            }
            if (c instanceof Container cc) {
                T r = find(cc, type, pred);
                if (r != null) return r;
            }
        }
        return null;
    }

    static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        java.util.concurrent.atomic.AtomicBoolean met = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do {
            try {
                SwingUtilities.invokeAndWait(() -> met.set(condition.getAsBoolean()));
            } catch (java.lang.reflect.InvocationTargetException ex) {
                throw new AssertionError("EDT condition failed: " + what, ex.getCause());
            }
            if (met.get()) break;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        check("timed out waiting for: " + what, met.get());
        if (!met.get()) throw new AssertionError("Timed out: " + what);
    }
}

interface BooleanSupplier {
    boolean getAsBoolean();
}
