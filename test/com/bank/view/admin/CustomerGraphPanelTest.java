package com.bank.view.admin;

import com.bank.graph.application.CustomerGraphService;
import com.bank.graph.domain.*;
import com.bank.security.AdminPrincipal;
import com.bank.security.ConfiguredAdminAuthenticationService;
import com.bank.service.CustomerService;
import com.bank.util.BCryptUtil;

import javax.swing.*;
import javax.swing.table.TableModel;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static com.bank.testkit.TestKit.*;

/** Drives the real Swing panel headlessly against a stub service. */
public class CustomerGraphPanelTest {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 12, 0);

    static class StubService implements CustomerGraphService {
        final List<GraphCriteria> criteriaSeen = new CopyOnWriteArrayList<>();
        final List<Set<Long>> detailSeen = new CopyOnWriteArrayList<>();
        final List<AdminPrincipal> principals = new CopyOnWriteArrayList<>();
        boolean truncated;

        @Override public CustomerGraph generateGraph(AdminPrincipal admin, GraphCriteria criteria) {
            principals.add(admin); criteriaSeen.add(criteria);
            CustomerIdentity a = new CustomerIdentity(1, "Ann Doe", false), b = new CustomerIdentity(2, "Bob Roe", true),
                    c = new CustomerIdentity(3, "Cy Poe", false);
            return CustomerGraphBuilder.build(new GraphSourceData(List.of(
                    new TransferFlow(a, b, 2, new BigDecimal("250.00"), T0),
                    new TransferFlow(b, c, 1, new BigDecimal("40.00"), T0)), truncated,
                    List.of(), false), criteria, T0);
        }

        @Override public List<TransferRecord> getTransfersInvolving(AdminPrincipal admin, Set<Long> ids) {
            principals.add(admin); detailSeen.add(ids);
            return List.of(new TransferRecord(77, new CustomerIdentity(1, "Ann Doe", false),
                    new CustomerIdentity(2, "Bob Roe", true), new BigDecimal("125.00"), "REF-77", "COMPLETED", T0));
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        AdminPrincipal admin = new ConfiguredAdminAuthenticationService("root", BCryptUtil.hash("pw"), Clock.systemUTC())
                .authenticate("root", "pw").orElseThrow();
        CustomerService customerService = (CustomerService) Proxy.newProxyInstance(
                CustomerService.class.getClassLoader(), new Class<?>[]{CustomerService.class},
                (p, m, a) -> { throw new UnsupportedOperationException(m.getName()); });
        StubService stub = new StubService();

        section("[1] Construction");
        checkThrows("panel refuses to be built without an administrator", NullPointerException.class,
                () -> new CustomerGraphPanel(null, stub, customerService));

        CustomerGraphPanel[] holder = new CustomerGraphPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new CustomerGraphPanel(admin, stub, customerService));
        CustomerGraphPanel panel = holder[0];
        SwingUtilities.invokeAndWait(() -> { panel.setSize(1300, 760); layoutDeep(panel); });

        JButton generate = find(panel, JButton.class, b -> "Generate Graph".equals(b.getText()));
        JButton ban = find(panel, JButton.class, b -> b.getText().startsWith("Ban"));
        JTable customers = find(panel, JTable.class, t -> t.getModel().getColumnName(1).equals("Customer"));
        JTable relationships = find(panel, JTable.class, t -> t.getModel().getColumnName(0).equals("Type"));
        JTable transfers = find(panel, JTable.class, t -> t.getModel().getColumnName(0).equals("Date"));
        JComboBox<?> period = find(panel, JComboBox.class, x -> true);

        check("Ban button disabled before anything is selected", !ban.isEnabled());
        checkEquals("no service call happens until Generate is clicked", 0, stub.criteriaSeen.size());

        section("[2] Generate");
        SwingUtilities.invokeAndWait(() -> { period.setSelectedIndex(1); generate.doClick(); });
        waitFor(() -> customers.getModel().getRowCount() > 0, "graph to load");
        checkEquals("service called once", 1, stub.criteriaSeen.size());
        checkEquals("panel passes the administrator principal to the service", admin, stub.principals.get(0));
        checkEquals("period option reaches the criteria", Integer.valueOf(30), stub.criteriaSeen.get(0).lookbackDays());
        check("shared-phone checkbox default is passed through", stub.criteriaSeen.get(0).includeSharedPhone());
        checkEquals("customer table lists all 3 customers", 3, customers.getModel().getRowCount());
        checkEquals("banned customer shown as BANNED", "BANNED", valueFor(customers.getModel(), 2L, 2));
        check("Generate button re-enabled after loading", generate.isEnabled());
        String summary = find(panel, JLabel.class, l -> l.getText().startsWith("Customers:")).getText();
        check("summary shows counts", summary.contains("Customers: 3") && summary.contains("Transfer links: 2")
                && summary.contains("Banned: 1") && summary.contains("last 30 days"));
        check("status says when the graph was generated",
                find(panel, JLabel.class, l -> l.getText().startsWith("Generated at")) != null);

        section("[3] Selection drives detail views");
        SwingUtilities.invokeAndWait(() -> customers.setRowSelectionInterval(0, 0));      // customer #1 (sorted by id)
        waitFor(() -> transfers.getModel().getRowCount() == 1, "transfer details to load (debounced)");
        checkEquals("relationships of customer 1: one edge (1→2)", 1, relationships.getModel().getRowCount());
        checkEquals("transfers requested for exactly the selected customer", List.of(Set.of(1L)), stub.detailSeen);
        checkEquals("transfer row shows reference", "REF-77", transfers.getModel().getValueAt(0, 6));
        check("Ban button enabled once something is selected", ban.isEnabled());

        SwingUtilities.invokeAndWait(() -> customers.addRowSelectionInterval(1, 1));       // add customer #2
        waitFor(() -> stub.detailSeen.size() == 2, "second detail request");
        checkEquals("multi-select requests both customers", Set.of(1L, 2L), stub.detailSeen.get(1));
        checkEquals("relationships of {1,2} include both edges", 2, relationships.getModel().getRowCount());

        SwingUtilities.invokeAndWait(customers::clearSelection);
        waitFor(() -> relationships.getModel().getRowCount() == 0 && transfers.getModel().getRowCount() == 0, "views to clear");
        check("clearing selection disables Ban and empties detail views", !ban.isEnabled());
        Thread.sleep(400);
        checkEquals("no stray detail request after deselecting (debounce cancelled)", 2, stub.detailSeen.size());

        section("[4] Truncation is communicated");
        stub.truncated = true;
        SwingUtilities.invokeAndWait(generate::doClick);
        waitFor(() -> stub.criteriaSeen.size() == 2, "second generation");
        waitFor(() -> find(panel, JLabel.class, l -> l.getText().contains("strongest links")) != null, "truncation notice");
        check("admin is told only the strongest links are shown",
                find(panel, JLabel.class, l -> l.getText().contains("strongest links")) != null);

        finish("CustomerGraphPanelTest");
        System.exit(0);
    }

    static Object valueFor(TableModel m, long id, int col) {
        for (int r = 0; r < m.getRowCount(); r++) if (((Number) m.getValueAt(r, 0)).longValue() == id) return m.getValueAt(r, col);
        return null;
    }

    static void layoutDeep(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) if (child instanceof Container cc) layoutDeep(cc);
    }

    static void waitFor(BooleanSupplier cond, String what) throws Exception {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = cond.getAsBoolean());
            if (ok[0]) return;
            Thread.sleep(40);
        }
        check("timed out waiting for " + what, false);
        finish("CustomerGraphPanelTest");
    }

    @SuppressWarnings("unchecked")
    static <T extends Component> T find(Container root, Class<T> type, java.util.function.Predicate<T> pred) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && pred.test((T) c)) return (T) c;
            if (c instanceof Container cc) { T r = find(cc, type, pred); if (r != null) return r; }
        }
        return null;
    }
}
