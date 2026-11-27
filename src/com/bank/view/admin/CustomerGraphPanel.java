package com.bank.view.admin;

import com.bank.graph.application.CustomerGraphService;
import com.bank.graph.domain.CustomerGraph;
import com.bank.graph.domain.CustomerNode;
import com.bank.graph.domain.GraphCriteria;
import com.bank.graph.domain.RelationshipEdge;
import com.bank.graph.domain.RelationshipType;
import com.bank.graph.domain.TransferRecord;
import com.bank.security.AdminPrincipal;
import com.bank.service.CustomerService;
import com.bank.view.DateUtil;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Administrator view of relationships between customers.
 *
 * <p>Presentation only: graph rules live in {@code com.bank.graph.domain}, data access behind
 * {@link CustomerGraphService}. All service calls run off the event-dispatch thread. The panel
 * is constructed with an {@link AdminPrincipal}, and every service call re-checks it.
 */
public class CustomerGraphPanel extends JPanel {

    private record PeriodOption(String label, Integer days) {
        @Override public String toString() { return label; }
    }

    private static final String[] CUSTOMER_COLUMNS = {"ID", "Customer", "Status", "Sent", "Received", "Links"};
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AdminPrincipal admin;
    private final CustomerGraphService graphService;
    private final CustomerService customerService;

    private final JComboBox<PeriodOption> periodCombo;
    private final JCheckBox sharedPhoneCheck;
    private final JButton generateButton;
    private final JButton banButton;
    private final JLabel statusLabel;
    private final JLabel summaryLabel;
    private final CustomerGraphCanvas canvas;
    private final DefaultTableModel customerModel;
    private final JTable customerTable;
    private final DefaultTableModel relationshipModel;
    private final DefaultTableModel transferModel;
    private final JLabel transferNote;
    private final Timer detailTimer;

    private CustomerGraph graph;
    private final Set<Long> selected = new LinkedHashSet<>();
    private boolean syncingSelection;
    private int detailRequest;          // only touched on the EDT; discards stale responses

    public CustomerGraphPanel(AdminPrincipal admin, CustomerGraphService graphService,
                              CustomerService customerService) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.graphService = Objects.requireNonNull(graphService, "graphService");
        this.customerService = Objects.requireNonNull(customerService, "customerService");

        setLayout(new BorderLayout(0, 10));
        setBackground(UITheme.SURFACE);
        setBorder(new EmptyBorder(12, 12, 12, 12));

        // ---- toolbar ---------------------------------------------------------------------------
        periodCombo = new JComboBox<>(new PeriodOption[]{
                new PeriodOption("All time", null), new PeriodOption("Last 30 days", 30),
                new PeriodOption("Last 90 days", 90), new PeriodOption("Last 365 days", 365)});
        sharedPhoneCheck = new JCheckBox("Link customers sharing a phone number", true);
        sharedPhoneCheck.setOpaque(false);
        generateButton = UIUtils.primaryButton("Generate Graph");
        banButton = UIUtils.dangerButton("Ban Selected Customers");
        banButton.setEnabled(false);
        generateButton.addActionListener(e -> loadGraph());
        banButton.addActionListener(e -> banSelected());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.setOpaque(false);
        controls.add(UIUtils.bodyLabel("Transfers:"));
        controls.add(periodCombo);
        controls.add(sharedPhoneCheck);
        controls.add(generateButton);
        controls.add(banButton);

        statusLabel = UIUtils.secondaryLabel("Choose options and click Generate Graph.");
        JPanel toolbar = new JPanel(new BorderLayout(0, 4));
        toolbar.setOpaque(false);
        toolbar.add(controls, BorderLayout.NORTH);
        toolbar.add(statusLabel, BorderLayout.SOUTH);
        add(toolbar, BorderLayout.NORTH);

        // ---- graph canvas ----------------------------------------------------------------------
        canvas = new CustomerGraphCanvas(this::onNodeClicked);
        JScrollPane graphScroll = new JScrollPane(canvas);
        graphScroll.getViewport().setBackground(UITheme.CARD);
        graphScroll.setBorder(BorderFactory.createTitledBorder("Customer relationship graph"));
        graphScroll.getVerticalScrollBar().setUnitIncrement(24);
        graphScroll.getHorizontalScrollBar().setUnitIncrement(24);

        // ---- tables ----------------------------------------------------------------------------
        customerModel = new DefaultTableModel(CUSTOMER_COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) { return c == 1 || c == 2 ? String.class : Long.class; }
        };
        customerTable = new JTable(customerModel);
        styleTable(customerTable);
        customerTable.setAutoCreateRowSorter(true);
        customerTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        customerTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !syncingSelection) selectionFromTable();
        });
        JScrollPane customersScroll = new JScrollPane(customerTable);
        customersScroll.setBorder(BorderFactory.createTitledBorder("Customers in the graph"));

        relationshipModel = new DefaultTableModel(
                new Object[]{"Type", "From", "To", "Transfers", "Total (" + DateUtil.CURRENCY + ")",
                        "Last activity", "Evidence"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable relationshipTable = new JTable(relationshipModel);
        styleTable(relationshipTable);

        transferModel = new DefaultTableModel(
                new Object[]{"Date", "ID", "From", "To", "Amount (" + DateUtil.CURRENCY + ")",
                        "Status", "Reference"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable transferTable = new JTable(transferModel);
        styleTable(transferTable);
        transferNote = UIUtils.secondaryLabel(" ");
        JPanel transferPanel = new JPanel(new BorderLayout(0, 4));
        transferPanel.setOpaque(false);
        transferPanel.add(new JScrollPane(transferTable), BorderLayout.CENTER);
        transferPanel.add(transferNote, BorderLayout.SOUTH);

        JTabbedPane detailTabs = new JTabbedPane();
        detailTabs.addTab("Relationships of selection", new JScrollPane(relationshipTable));
        detailTabs.addTab("All transfers of selection", transferPanel);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, customersScroll, detailTabs);
        rightSplit.setResizeWeight(0.5);
        rightSplit.setBorder(null);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphScroll, rightSplit);
        mainSplit.setResizeWeight(0.62);
        mainSplit.setDividerLocation(700);
        mainSplit.setBorder(null);
        add(mainSplit, BorderLayout.CENTER);

        summaryLabel = UIUtils.secondaryLabel(
                "Links are investigation leads, not proof of wrongdoing. Only COMPLETED transfers between "
                        + "different customers are drawn.");
        summaryLabel.setBorder(new EmptyBorder(2, 2, 0, 2));
        add(summaryLabel, BorderLayout.SOUTH);

        detailTimer = new Timer(250, e -> loadTransferDetails());
        detailTimer.setRepeats(false);
    }

    private void styleTable(JTable table) {
        UIUtils.styleTable(table);
        table.setFont(UITheme.FONT_BODY);
        table.setRowHeight(26);
        table.setGridColor(UITheme.BORDER);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(UITheme.SELECTION_BG);
    }

    // ==========================================================================================
    // Generate
    // ==========================================================================================
    private void loadGraph() {
        PeriodOption period = (PeriodOption) Objects.requireNonNull(periodCombo.getSelectedItem());
        GraphCriteria criteria = new GraphCriteria(period.days(), sharedPhoneCheck.isSelected(),
                GraphCriteria.DEFAULT_MAX_EDGES);
        setBusy(true, "Building the customer graph…");

        new SwingWorker<CustomerGraph, Void>() {
            @Override protected CustomerGraph doInBackground() {
                return graphService.generateGraph(admin, criteria);
            }
            @Override protected void done() {
                setBusy(false, null);
                try {
                    showGraph(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Graph generation was interrupted.");
                } catch (ExecutionException e) {
                    statusLabel.setText("Could not generate the graph.");
                    UIUtils.showError(CustomerGraphPanel.this,
                            "Failed to load the customer graph: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private void showGraph(CustomerGraph newGraph) {
        graph = newGraph;
        selected.clear();
        canvas.setGraph(newGraph);

        // One table event for all rows (instead of one per row), keeping the admin's chosen sort order.
        Object[][] rows = new Object[newGraph.nodes().size()][];
        for (int i = 0; i < rows.length; i++) {
            CustomerNode n = newGraph.nodes().get(i);
            rows[i] = new Object[]{n.id(), n.displayName(), n.banned() ? "BANNED" : "ACTIVE",
                    n.transfersSent(), n.transfersReceived(), (long) n.connections()};
        }
        List<? extends RowSorter.SortKey> sortKeys = customerTable.getRowSorter().getSortKeys();
        syncingSelection = true;      // the rebuild fires selection events that must not be treated as user input
        try {
            customerModel.setDataVector(rows, CUSTOMER_COLUMNS);
            customerTable.getRowSorter().setSortKeys(sortKeys);
        } finally {
            syncingSelection = false;
        }
        UIUtils.adjustColumnWidths(customerTable, 50, 200);

        summaryLabel.setText("Customers: " + newGraph.nodes().size()
                + "   |   Transfer links: " + newGraph.countEdges(RelationshipType.MONEY_TRANSFER)
                + "   |   Completed transfers: " + newGraph.totalTransfers()
                + "   |   Shared-phone links: " + newGraph.countEdges(RelationshipType.SHARED_PHONE)
                + "   |   Banned: " + newGraph.bannedCount()
                + "   |   Period: " + newGraph.criteria().describePeriod());

        StringBuilder status = new StringBuilder("Generated at ")
                .append(newGraph.generatedAt().format(CLOCK)).append(". ");
        if (newGraph.isEmpty()) status.append("No relationships found. ");
        if (newGraph.isTruncated()) {
            status.append("Showing only the strongest links (limit ").append(newGraph.criteria().maxEdges())
                    .append(" per type reached) — choose a shorter period to see everything. ");
        }
        if (newGraph.omittedSharedGroups() > 0) {
            status.append(newGraph.omittedSharedGroups())
                    .append(" phone number(s) shared by too many customers were skipped. ");
        }
        statusLabel.setText(status.toString().trim());
        refreshSelectionViews();
    }

    private void setBusy(boolean busy, String message) {
        generateButton.setEnabled(!busy);
        periodCombo.setEnabled(!busy);
        sharedPhoneCheck.setEnabled(!busy);
        if (busy) {
            banButton.setEnabled(false);
            statusLabel.setText(message);
        }
    }

    // ==========================================================================================
    // Selection
    // ==========================================================================================
    private void onNodeClicked(long id, boolean additive) {
        if (graph == null || graph.node(id).isEmpty()) return;
        if (!additive) {
            selected.clear();
            selected.add(id);
        } else if (!selected.remove(id)) {
            selected.add(id);
        }
        refreshSelectionViews();
    }

    private void selectionFromTable() {
        selected.clear();
        for (int viewRow : customerTable.getSelectedRows()) {
            int modelRow = customerTable.convertRowIndexToModel(viewRow);
            selected.add(((Number) customerModel.getValueAt(modelRow, 0)).longValue());
        }
        refreshSelectionViews();
    }

    private void refreshSelectionViews() {
        syncingSelection = true;
        try {
            customerTable.clearSelection();
            for (int modelRow = 0; modelRow < customerModel.getRowCount(); modelRow++) {
                long id = ((Number) customerModel.getValueAt(modelRow, 0)).longValue();
                if (selected.contains(id)) {
                    int viewRow = customerTable.convertRowIndexToView(modelRow);
                    customerTable.addRowSelectionInterval(viewRow, viewRow);
                }
            }
        } finally {
            syncingSelection = false;
        }
        canvas.setSelection(selected);
        populateRelationships();
        banButton.setEnabled(!selected.isEmpty() && generateButton.isEnabled());

        transferModel.setRowCount(0);
        if (selected.isEmpty()) {
            detailTimer.stop();
            detailRequest++;
            transferNote.setText(" ");
        } else {
            transferNote.setText("Loading transfers…");
            detailTimer.restart();           // debounce rapid multi-select clicks
        }
    }

    private void populateRelationships() {
        relationshipModel.setRowCount(0);
        if (graph == null || selected.isEmpty()) return;
        for (RelationshipEdge e : graph.edgesInvolving(selected)) {
            boolean transfer = e.type() == RelationshipType.MONEY_TRANSFER;
            relationshipModel.addRow(new Object[]{
                    e.type().label(), label(e.fromId()), label(e.toId()),
                    transfer ? e.transferCount() : "—",
                    transfer ? DateUtil.formatAmount(e.totalAmount()) : "—",
                    e.lastActivity() == null ? "—" : DateUtil.formatDateTime(e.lastActivity()),
                    e.evidence().isEmpty() ? "—" : e.evidence()});
        }
    }

    private String label(long id) {
        return graph.node(id).map(n -> n.displayName() + " (#" + id + ")").orElse("#" + id);
    }

    private void loadTransferDetails() {
        if (selected.isEmpty()) return;
        final int request = ++detailRequest;
        final Set<Long> ids = Set.copyOf(selected);

        new SwingWorker<List<TransferRecord>, Void>() {
            @Override protected List<TransferRecord> doInBackground() {
                return graphService.getTransfersInvolving(admin, ids);
            }
            @Override protected void done() {
                if (request != detailRequest) return;       // a newer selection superseded this one
                transferModel.setRowCount(0);
                try {
                    List<TransferRecord> rows = get();
                    for (TransferRecord t : rows) {
                        transferModel.addRow(new Object[]{
                                DateUtil.formatDateTime(t.createdAt()), t.transferId(),
                                t.sender().displayName() + " (#" + t.sender().id() + ")",
                                t.receiver().displayName() + " (#" + t.receiver().id() + ")",
                                DateUtil.formatAmount(t.amount()), t.status(), t.referenceNumber()});
                    }
                    transferNote.setText(rows.size() >= CustomerGraphService.MAX_DETAIL_ROWS
                            ? "Showing the newest " + rows.size() + " transfers (all statuses)."
                            : rows.size() + " transfer(s), all statuses.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    transferNote.setText("Could not load transfers: " + rootMessage(e));
                }
            }
        }.execute();
    }

    // ==========================================================================================
    // Ban
    // ==========================================================================================
    private void banSelected() {
        if (graph == null || selected.isEmpty()) return;
        List<CustomerNode> targets = selected.stream()
                .map(id -> graph.node(id).orElse(null))
                .filter(n -> n != null && !n.banned())
                .toList();
        if (targets.isEmpty()) {
            UIUtils.showWarning(this, "All selected customers are already banned.");
            return;
        }
        String reason = UIUtils.inputDialog(this,
                "Enter the reason for banning the selected customer(s):", "Ban Customers");
        if (reason == null) return;
        if (reason.trim().isEmpty()) {
            UIUtils.showWarning(this, "A ban reason is required.");
            return;
        }
        String names = targets.stream().map(n -> n.displayName() + " (#" + n.id() + ")")
                .collect(Collectors.joining("\n"));
        if (!UIUtils.confirmDialog(this, "This will block login and lock every account owned by these "
                + "customer(s):\n\n" + names + "\n\nContinue?")) return;

        final String cleanReason = reason.trim();
        setBusy(true, "Applying customer bans…");
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() {
                List<String> results = new ArrayList<>();
                for (CustomerNode n : targets) {
                    try {
                        customerService.banCustomer(admin, n.id(), cleanReason);   // atomic per customer
                        results.add("BANNED: " + n.displayName() + " (#" + n.id() + ")");
                    } catch (RuntimeException ex) {
                        results.add("FAILED: " + n.displayName() + " (#" + n.id() + ") — " + ex.getMessage());
                    }
                }
                return results;
            }
            @Override protected void done() {
                setBusy(false, null);
                try {
                    UIUtils.showWarning(CustomerGraphPanel.this, String.join("\n", get()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    UIUtils.showError(CustomerGraphPanel.this, "Ban operation failed: " + rootMessage(e));
                }
                loadGraph();      // show the new statuses
            }
        }.execute();
    }

    private static String rootMessage(ExecutionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
