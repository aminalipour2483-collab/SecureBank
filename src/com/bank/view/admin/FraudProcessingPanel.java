package com.bank.view.admin;

import com.bank.admin.AdminCompositionRoot;
import com.bank.fraud.outbox.FraudEventSummary;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.view.UIUtils;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Administrative overview of durable events, including LOW results and retryable failures. */
public final class FraudProcessingPanel extends JPanel implements AdminFrame.Refreshable {
    private final AdminPrincipal admin;
    private final java.util.function.BiFunction<AdminPrincipal, Boolean, List<FraudEventSummary>> loader;
    private final java.util.function.BiConsumer<AdminPrincipal, Long> retryAction;
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Event ID","Account ID","Type","Status","Attempt","Cycle","Score","Risk"},0) {
        @Override public boolean isCellEditable(int row,int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JCheckBox failedOnly = new JCheckBox("Failed only");
    private final JButton refresh = UIUtils.ghostButton("Refresh");
    private final JButton retry = UIUtils.primaryButton("Retry selected FAILED event");
    private final JButton details = UIUtils.ghostButton("Details");
    private final JLabel status = new JLabel("Latest 200 events; refresh to update.");
    private List<FraudEventSummary> rows = List.of();
    private boolean busy;

    public FraudProcessingPanel(AdminPrincipal admin) {
        this(admin, (principal, filter) -> AdminCompositionRoot.getInstance().recentFraudEvents(principal, filter),
                (principal, id) -> AdminCompositionRoot.getInstance().retryFailedFraudEvent(principal, id));
    }
    FraudProcessingPanel(AdminPrincipal admin,
            java.util.function.BiFunction<AdminPrincipal, Boolean, List<FraudEventSummary>> loader,
            java.util.function.BiConsumer<AdminPrincipal, Long> retryAction) {
        this.admin = AdminGuard.require(admin);
        this.loader = java.util.Objects.requireNonNull(loader);
        this.retryAction = java.util.Objects.requireNonNull(retryAction);
        setLayout(new BorderLayout(8,8));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(refresh); bar.add(failedOnly); bar.add(details); bar.add(retry);
        add(bar, BorderLayout.NORTH); add(new JScrollPane(table), BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        refresh.addActionListener(e -> refresh()); failedOnly.addActionListener(e -> refresh());
        details.addActionListener(e -> showDetails()); retry.addActionListener(e -> retrySelected());
        table.getSelectionModel().addListSelectionListener(e -> updateControls());
        updateControls();
    }
    private FraudEventSummary selected() {
        int row=table.getSelectedRow(); return row<0 ? null : rows.get(table.convertRowIndexToModel(row));
    }
    private void updateControls() {
        FraudEventSummary selected = selected();
        refresh.setEnabled(!busy); failedOnly.setEnabled(!busy);
        details.setEnabled(!busy && selected!=null);
        retry.setEnabled(!busy && selected!=null && "FAILED".equals(selected.status()));
    }
    @Override public void refresh() {
        if (busy) return;
        boolean filter=failedOnly.isSelected(); busy=true; updateControls(); status.setText("Loading…");
        new SwingWorker<List<FraudEventSummary>, Void>() {
            @Override protected List<FraudEventSummary> doInBackground() { return loader.apply(admin, filter); }
            @Override protected void done() {
                try {
                    List<FraudEventSummary> loaded=get(); table.clearSelection(); model.setRowCount(0); rows=loaded;
                    for (FraudEventSummary r:rows) model.addRow(new Object[]{r.id(),r.accountId(),r.type(),r.status(),r.attempt(),r.cycle(),r.score(),r.risk()});
                    status.setText("Showing " + rows.size() + " of the latest matching events (max 200).");
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); status.setText("Refresh interrupted."); }
                catch (ExecutionException ex) { status.setText("Unable to load events. Check database connection and P6 migration."); }
                finally { busy=false; updateControls(); }
            }
        }.execute();
    }
    private void showDetails() {
        FraudEventSummary r=selected(); if (r==null) return;
        JTextArea text=new JTextArea("Event: " + r.id() + "\nStatus: " + r.status() + "\nRules: " + value(r.rules()) + "\n\n" + value(r.details()) + "\n\nLast error: " + value(r.error()), 14, 55);
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        JOptionPane.showMessageDialog(this,new JScrollPane(text),"Fraud processing details",JOptionPane.INFORMATION_MESSAGE);
    }
    private static String value(String s) { return s==null ? "—" : s; }
    private void retrySelected() {
        FraudEventSummary r=selected(); if(busy || r==null || !"FAILED".equals(r.status())) return;
        if(!UIUtils.confirmDialog(this,"Schedule a new retry cycle for event #"+r.id()+"?")) return;
        busy=true; updateControls(); status.setText("Scheduling retry…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() { retryAction.accept(admin,r.id()); return null; }
            @Override protected void done() {
                boolean success=false;
                try { get(); success=true; }
                catch(InterruptedException ex) { Thread.currentThread().interrupt(); status.setText("Retry interrupted; refresh to verify."); }
                catch(ExecutionException ex) { status.setText("Retry not confirmed; refresh to verify event status."); }
                finally {busy=false; updateControls();}
                if(success) refresh();
            }
        }.execute();
    }
}
