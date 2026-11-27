package com.bank.view.admin;

import com.bank.fraudring.application.FraudRingService;
import com.bank.fraudring.domain.FraudRingAnalysis;
import com.bank.fraudring.domain.FraudRingCriteria;
import com.bank.fraudring.domain.RiskBand;
import com.bank.security.AdminPrincipal;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FraudRingPanel — the admin UI of the fraud-ring module (Phase 4).
 *
 * <p>The panel drives the SAME analysis pipeline Phases 2–3 built:
 * criteria form → {@link FraudRingService#analyze} (worker thread) →
 * ranked customers/groups tables with band colours → the XAI reason dialog
 * showing the Persian reason sentences with their weights.
 *
 * <p><b>Layering.</b> View code only talks to the application service; it
 * parses the STORED JSON payload (single source of truth: what the panel
 * shows is exactly what the audit row kept). No DAO, no SQL — enforced by
 * ArchitectureRulesTest like every admin view.
 *
 * <p><b>XAI.</b> Double-click a customer/group row → dialog lists every
 * reason: Persian title, weight, Persian detail — byte-for-byte the reason
 * the scoring engine produced, so a manager can always answer "why 85?".
 *
 * <p><b>Replay.</b> Identical criteria produce the identical idempotency
 * key, so re-running the same analysis replays the stored row (the service
 * does this); the panel additionally shows the key on the status line.
 */
public class FraudRingPanel extends JPanel implements AdminFrame.Refreshable {

    // payload field indices (see FraudRingPayloads)
    private static final String[] CUSTOMER_COLUMNS =
            {"Rank", "Customer", "Score", "Band", "Community", "Cycles", "Degree", "Signals"};
    private static final String[] GROUP_COLUMNS =
            {"Rank", "Community", "Score", "Band", "Size", "Members", "Signals"};

    private final AdminPrincipal admin;
    private final FraudRingService fraudRingService;

    private final JComboBox<LookbackOption> lookbackCombo;
    private final JCheckBox sharedPhoneCheck;
    private final JSpinner scopeIdSpinner;
    private final JRadioButton scopeFullRadio;
    private final JRadioButton scopeSingleRadio;
    private final JButton analyzeButton;
    private final JLabel statusLabel;
    private final JLabel summaryLabel;

    private final DefaultTableModel customerModel;
    private final JTable customerTable;
    private final DefaultTableModel groupModel;
    private final JTable groupTable;

    /** The analysis whose payload is on screen; null before the first run. */
    private FraudRingAnalysis current;

    /** Parsed rows of the current payload; empty before the first run. */
    private List<CustomerRow> customerRows = List.of();
    private List<GroupRow> groupRows = List.of();

    // ─────────────────────────────────────────────────────────────────────────
    // construction
    // ─────────────────────────────────────────────────────────────────────────

    /** @throws com.bank.exception.AccessDeniedException if {@code admin} is null */
    public FraudRingPanel(AdminPrincipal admin, FraudRingService fraudRingService) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.fraudRingService = Objects.requireNonNull(fraudRingService, "fraudRingService");
        setLayout(new BorderLayout(0, 0));
        setBackground(UITheme.SURFACE);
        setOpaque(true);

        // ── criteria form ──
        JPanel toolbar = new JPanel(new BorderLayout(0, 8));
        toolbar.setBackground(UITheme.CARD);
        toolbar.setBorder(new EmptyBorder(10, 12, 10, 12));

        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        form.setBackground(UITheme.CARD);
        form.add(UIUtils.secondaryLabel("Scope:"));
        scopeFullRadio = new JRadioButton("All customers", true);
        scopeSingleRadio = new JRadioButton("Customer #");
        scopeIdSpinner = new JSpinner(new SpinnerNumberModel(1L, 1L, Long.MAX_VALUE, 1L));
        scopeIdSpinner.setEnabled(false);
        ButtonGroup scopeGroup = new ButtonGroup();
        scopeGroup.add(scopeFullRadio);
        scopeGroup.add(scopeSingleRadio);
        scopeFullRadio.addActionListener(e -> scopeIdSpinner.setEnabled(false));
        scopeSingleRadio.addActionListener(e -> scopeIdSpinner.setEnabled(true));
        form.add(scopeFullRadio);
        form.add(scopeSingleRadio);
        form.add(scopeIdSpinner);
        form.add(UIUtils.secondaryLabel("Period:"));
        lookbackCombo = new JComboBox<>(LookbackOption.values());
        form.add(lookbackCombo);
        sharedPhoneCheck = new JCheckBox("Shared phones", true);
        form.add(sharedPhoneCheck);
        analyzeButton = UIUtils.primaryButton("Analyze Ring");
        analyzeButton.addActionListener(e -> runAnalysis());
        form.add(analyzeButton);
        analyzeButton.setToolTipText("Run the graph analysis and risk scoring (idempotent: the same criteria replay the stored row)");

        statusLabel = UIUtils.secondaryLabel("Choose criteria and click Analyze Ring.");
        summaryLabel = UIUtils.secondaryLabel("");

        toolbar.add(form, BorderLayout.NORTH);
        JPanel statusLine = new JPanel(new BorderLayout());
        statusLine.setBackground(UITheme.CARD);
        statusLine.add(statusLabel, BorderLayout.CENTER);
        statusLine.add(summaryLabel, BorderLayout.EAST);
        toolbar.add(statusLine, BorderLayout.SOUTH);

        // ── tables ──
        customerModel = new DefaultTableModel(CUSTOMER_COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
            @Override public Class<?> getColumnClass(int col) {
                return col == 0 || col == 5 || col == 6 ? Integer.class : Object.class;
            }
        };
        customerTable = new JTable(customerModel);
        UIUtils.styleTable(customerTable);
        customerTable.setAutoCreateRowSorter(true);
        customerTable.setDefaultRenderer(Object.class, new BandRenderer());
        customerTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) showReasonsDialog(selectedCustomerRow());
            }
        });

        groupModel = new DefaultTableModel(GROUP_COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
            @Override public Class<?> getColumnClass(int col) {
                return col == 0 || col == 4 ? Integer.class : Object.class;
            }
        };
        groupTable = new JTable(groupModel);
        UIUtils.styleTable(groupTable);
        groupTable.setAutoCreateRowSorter(true);
        groupTable.setDefaultRenderer(Object.class, new BandRenderer());
        groupTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) showReasonsDialog(selectedGroupRow());
            }
        });

        JSplitPane tables = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                scrollWithTitle(customerTable, "Top risk customers"),
                scrollWithTitle(groupTable, "Risk rings (communities)"));
        tables.setResizeWeight(0.55);
        tables.setBorder(new EmptyBorder(6, 12, 12, 12));
        tables.setBackground(UITheme.SURFACE);

        add(toolbar, BorderLayout.NORTH);
        add(tables, BorderLayout.CENTER);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // analysis run
    // ─────────────────────────────────────────────────────────────────────────

    private void runAnalysis() {
        FraudRingCriteria built;
        try {
            if (scopeSingleRadio.isSelected()) {
                built = FraudRingCriteria.single((Long) scopeIdSpinner.getValue());
            } else {
                built = FraudRingCriteria.full();
            }
            LookbackOption period = (LookbackOption) Objects.requireNonNull(lookbackCombo.getSelectedItem());
            built = withPeriod(built, period);
            // shared-phone toggle: same scope/period, new criteria object
            built = new FraudRingCriteria(built.scope(), built.customerIds(),
                    built.lookbackDays(), sharedPhoneCheck.isSelected(), built.maxNodes());
        } catch (RuntimeException bad) {
            showNotice("Invalid criteria: " + bad.getMessage());
            return;
        }
        final FraudRingCriteria criteria = built;

        setBusy(true, "Analysing the transfer graph…");
        new SwingWorker<FraudRingAnalysis, Void>() {
            @Override protected FraudRingAnalysis doInBackground() {
                return fraudRingService.analyze(admin, criteria);
            }
            @Override protected void done() {
                try {
                    showAnalysis(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Analysis was interrupted.");
                } catch (ExecutionException e) {
                    statusLabel.setText("Could not complete the analysis.");
                    showNotice("Failed to run the fraud-ring analysis: " + rootMessage(e));
                } finally {
                    setBusy(false, null);
                }
            }
        }.execute();
    }

    private static FraudRingCriteria withPeriod(FraudRingCriteria base, LookbackOption period) {
        if (base.lookbackDays() == null && period.days() == null) return base;
        return new FraudRingCriteria(base.scope(), base.customerIds(), period.days(),
                base.includeSharedPhone(), base.maxNodes());
    }

    private void setBusy(boolean busy, String message) {
        analyzeButton.setEnabled(!busy);
        if (message != null) statusLabel.setText(message);
    }

    private void showAnalysis(FraudRingAnalysis analysis) {
        this.current = analysis;
        this.customerRows = parseCustomers(analysis.topCustomersJson());
        this.groupRows = parseGroups(analysis.topGroupsJson());

        Object[][] cRows = new Object[customerRows.size()][];
        for (int i = 0; i < customerRows.size(); i++) {
            CustomerRow r = customerRows.get(i);
            cRows[i] = new Object[]{i + 1, r.displayName + " (#" + r.customerId + ")",
                    r.score, r.band.persianLabel(), r.communityId, r.cycleCount, r.degree,
                    r.reasons.stream().map(Reason::code).reduce((a, b) -> a + "+" + b).orElse("—")};
        }
        Object[][] gRows = new Object[groupRows.size()][];
        for (int i = 0; i < groupRows.size(); i++) {
            GroupRow r = groupRows.get(i);
            gRows[i] = new Object[]{i + 1, "Ring " + r.communityId, r.score, r.band.persianLabel(),
                    r.memberIds.size(), r.membersSummary(), r.reasons.stream()
                            .map(Reason::code).reduce((a, b) -> a + "+" + b).orElse("—")};
        }
        customerModel.setDataVector(cRows, CUSTOMER_COLUMNS);
        groupModel.setDataVector(gRows, GROUP_COLUMNS);

        statusLabel.setText("Analysis #" + analysis.analysisId()
                + " · key " + shortKey(analysis.idempotencyKey())
                + " · " + nodeEdgeSummary(analysis.graphStatsJson()));
        summaryLabel.setText(bandSummary(customerRows));
        if (customerRows.isEmpty()) {
            showNotice("No transfer activity matched these criteria.");
        }
    }

    /** Headless-safe notice: dialog when a GUI is available, status line otherwise. */
    private void showNotice(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            statusLabel.setText(message);
        } else {
            UIUtils.showSuccess(this, message);
        }
    }

    private static JScrollPane scrollWithTitle(JTable table, String title) {
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder(title));
        return scroll;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // XAI dialog
    // ─────────────────────────────────────────────────────────────────────────

    private CustomerRow selectedCustomerRow() {
        int viewRow = customerTable.getSelectedRow();
        if (viewRow < 0) return null;
        int modelRow = customerTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= customerRows.size()) return null;
        return customerRows.get(modelRow);
    }

    private GroupRow selectedGroupRow() {
        int viewRow = groupTable.getSelectedRow();
        if (viewRow < 0) return null;
        int modelRow = groupTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= groupRows.size()) return null;
        return groupRows.get(modelRow);
    }

    private void showReasonsDialog(CustomerRow row) {
        if (row == null) return;
        List<Reason> reasons = row.reasons;
        String header = row.displayName + " (#" + row.customerId + ") — score " + row.score
                + " / " + row.band.persianLabel();
        showReasonDialog("Customer risk", header, row.score, reasons);
    }

    private void showReasonsDialog(GroupRow row) {
        if (row == null) return;
        List<Reason> reasons = row.reasons;
        String header = "Ring " + row.communityId + " · " + row.memberIds.size() + " members — score "
                + row.score + " / " + row.band.persianLabel();
        showReasonDialog("Ring risk", header, row.score, reasons);
    }

    /** The XAI view: every reason with its weight and its Persian sentence, verbatim. */
    private void showReasonDialog(String title, String header, int score, List<Reason> reasons) {
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBackground(UITheme.CARD);
        content.setBorder(new EmptyBorder(14, 16, 14, 16));

        JLabel head = new JLabel(header);
        head.setFont(UITheme.FONT_SUBHEADING);
        content.add(head, BorderLayout.NORTH);

        String[] columns = {"Signal", "+", "Why (XAI)"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        for (Reason reason : reasons) {
            model.addRow(new Object[]{reason.code(), reason.points(), reason.detail()});
        }
        if (reasons.isEmpty()) {
            model.addRow(new Object[]{"CLEAN", 0, "هیچ سیگنال خطری برای این ردیف فعال نشد."});
        }
        JTable table = new JTable(model);
        UIUtils.styleTable(table);
        table.getColumnModel().getColumn(1).setMaxWidth(48);
        table.getColumnModel().getColumn(2).setPreferredWidth(520);
        table.setRowHeight(34);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(680, 300));

        content.add(scroll, BorderLayout.CENTER);
        content.add(new JLabel("Score = " + score + " = sum of the weights above"
                + (score == 100 ? " (capped at 100)" : "") + " · امتیاز = مجموع وزن سیگنال‌ها"),
                BorderLayout.SOUTH);

        if (GraphicsEnvironment.isHeadless()) {
            // No display: keep the built content for tests instead of a Window
            // (any Window constructor throws HeadlessException).
            lastDialogContent = content;
            return;
        }
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this),
                title + " — why this score", true);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // payload parsing (the stored JSON is the single source of truth)
    // ─────────────────────────────────────────────────────────────────────────

    record Reason(String code, int points, String title, String detail) {}

    record CustomerRow(long customerId, String displayName, int score, RiskBand band,
                       int communityId, int cycleCount, int degree, List<Reason> reasons) {}

    record GroupRow(int communityId, List<Long> memberIds, int score, RiskBand band,
                    double density, String internalVolume, List<Reason> reasons) {

        String membersSummary() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(memberIds.size(), 6); i++) {
                if (i > 0) sb.append(' ');
                sb.append(memberIds.get(i));
            }
            if (memberIds.size() > 6) sb.append(" +").append(memberIds.size() - 6).append(" more");
            return sb.toString();
        }
    }

    // Hand-rolled flat-JSON scalpers: the payloads are schema-stable (FraudRingPayloads),
    // so targeted field extraction beats shipping a JSON library on this classpath.

    static List<CustomerRow> parseCustomers(String json) {
        List<CustomerRow> rows = new ArrayList<>();
        if (json == null || json.isBlank()) return rows;
        int arrayAt = json.indexOf("\"customers\":[");
        if (arrayAt < 0) return rows;
        for (String item : braceItems(json.substring(arrayAt + 12))) {
            if (!item.contains("\"customerId\"")) continue;
            rows.add(new CustomerRow(
                    longField(item, "customerId"),
                    stringField(item, "displayName"),
                    (int) longField(item, "score"),
                    RiskBand.of((int) longField(item, "score")),
                    (int) longField(item, "communityId"),
                    (int) longField(item, "cycleCount"),
                    (int) longField(item, "degree"),
                    parseReasons(item)));
        }
        return rows;
    }

    static List<GroupRow> parseGroups(String json) {
        List<GroupRow> rows = new ArrayList<>();
        if (json == null || json.isBlank()) return rows;
        int arrayAt = json.indexOf("\"groups\":[");
        if (arrayAt < 0) return rows;
        for (String item : braceItems(json.substring(arrayAt + 10))) {
            if (!item.contains("\"communityId\"")) continue;
            rows.add(new GroupRow(
                    (int) longField(item, "communityId"),
                    longArray(item, "members"),
                    (int) longField(item, "score"),
                    RiskBand.of((int) longField(item, "score")),
                    doubleField(item, "density"),
                    stringField(item, "internalVolume"),
                    parseReasons(item)));
        }
        return rows;
    }

    private static List<Reason> parseReasons(String item) {
        List<Reason> reasons = new ArrayList<>();
        int reasonsAt = item.indexOf("\"reasons\":[");
        if (reasonsAt < 0) return reasons;
        for (String reason : braceItems(item.substring(reasonsAt + 11))) {
            if (!reason.contains("\"code\"")) continue;
            reasons.add(new Reason(stringField(reason, "code"),
                    (int) longField(reason, "points"),
                    stringField(reason, "title"),
                    stringField(reason, "detail")));
        }
        return reasons;
    }

    /**
     * Splits a flat JSON record array into its top-level objects, correctly
     * skipping nested braces (reasons inside customers) and escaped quotes.
     * A simple regex cannot do this: {@code \{[^{}]*\}} matches only the
     * innermost objects.
     */
    private static List<String> braceItems(String arrayAndRest) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inString = false, escaped = false;
        for (int i = 0; i < arrayAndRest.length(); i++) {
            char c = arrayAndRest.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(arrayAndRest.substring(start, i + 1));
                    start = -1;
                }
                if (depth < 0) break;      // walked past the array
            }
        }
        return out;
    }

    private static long longField(String item, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(-?\\d+)").matcher(item);
        if (!m.find()) throw new IllegalArgumentException("payload field missing: " + field);
        return Long.parseLong(m.group(1));
    }

    private static double doubleField(String item, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":([0-9.]+)").matcher(item);
        if (!m.find()) throw new IllegalArgumentException("payload field missing: " + field);
        return Double.parseDouble(m.group(1));
    }

    private static String booleanField(String item, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(true|false)").matcher(item);
        if (!m.find()) throw new IllegalArgumentException("payload field missing: " + field);
        return m.group(1);
    }

    private static String stringField(String item, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(item);
        if (!m.find()) throw new IllegalArgumentException("payload field missing: " + field);
        return unescape(m.group(1));
    }

    private static List<Long> longArray(String item, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\\[([\\d,\\s]*)]").matcher(item);
        if (!m.find()) throw new IllegalArgumentException("payload field missing: " + field);
        List<Long> ids = new ArrayList<>();
        for (String part : m.group(1).split(",")) {
            if (!part.isBlank()) ids.add(Long.parseLong(part.trim()));
        }
        return ids;
    }

    private static String unescape(String raw) {
        return raw.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    private static String bandSummary(List<CustomerRow> rows) {
        if (rows.isEmpty()) return "No customers scored";
        int low = 0, medium = 0, high = 0, critical = 0;
        for (CustomerRow r : rows) {
            switch (r.band) {
                case LOW -> low++;
                case MEDIUM -> medium++;
                case HIGH -> high++;
                case CRITICAL -> critical++;
            }
        }
        return "کم " + low + " · متوسط " + medium + " · زیاد " + high + " · بحرانی " + critical;
    }

    private static String nodeEdgeSummary(String graphStats) {
        if (graphStats == null) return "";
        try {
            String summary = longField(graphStats, "nodeCount") + " customers · "
                    + longField(graphStats, "cycleCount") + " cycles";
            try {
                if ("true".equals(booleanField(graphStats, "cycleDetectionTruncated"))) {
                    summary += " · cycle list capped (partial)";
                }
            } catch (RuntimeException olderPayload) {
                // Previously persisted analyses predate the explicit cycle cap status.
            }
            try {
                String model = stringField(graphStats, "anomalyModel");
                long baseline = longField(graphStats, "anomalyBaselineSize");
                String modelLabel = switch (model) {
                    case "ACTIVE" -> "active";
                    case "INCOMPLETE_SNAPSHOT" -> "disabled: incomplete snapshot";
                    default -> "insufficient baseline";
                };
                summary += " · Isolation Forest: " + modelLabel + " (n=" + baseline + ")";
            } catch (RuntimeException olderPayload) {
                // Previously persisted analyses predate the unsupervised model metadata.
            }
            return summary;
        } catch (RuntimeException bad) {
            return "";
        }
    }

    private static String shortKey(String key) {
        return key == null ? "—" : key.substring(0, Math.min(12, key.length())) + "…";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // misc
    // ─────────────────────────────────────────────────────────────────────────

    /** Period presets — same UX as the Customer Graph panel's period combo. */
    enum LookbackOption {
        ALL_TIME("All time", null),
        LAST_30("Last 30 days", 30),
        LAST_90("Last 90 days", 90),
        LAST_365("Last year", 365);

        private final String label;
        private final Integer days;

        LookbackOption(String label, Integer days) {
            this.label = label;
            this.days = days;
        }

        Integer days() { return days; }

        @Override public String toString() { return label; }
    }

    /** Band-tinted renderer: the row reads at a glance (RED / amber / blue / neutral). */
    private final class BandRenderer extends DefaultTableCellRenderer {
        @Override public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focus, int row, int col) {
            java.awt.Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            if (selected) return c;
            RiskBand band = bandOfRow(table, table.convertRowIndexToModel(row));
            if (band != null) c.setBackground(bandTint(band));
            return c;
        }
    }

    private RiskBand bandOfRow(JTable table, int modelRow) {
        Object score = table.getModel().getValueAt(modelRow, 2);   // "Score" column
        return score instanceof Integer points ? RiskBand.of(points) : null;
    }

    static Color bandTint(RiskBand band) {
        return switch (band) {
            case CRITICAL -> new Color(0xFB, 0xE3, 0xE3);           // light red
            case HIGH -> new Color(0xFC, 0xEC, 0xD4);                // light amber
            case MEDIUM -> new Color(0xE4, 0xEE, 0xFA);              // light blue
            case LOW -> UITheme.CARD;
        };
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Refreshable (AdminFrame switches to this tab)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void refresh() {
        // Nothing to reload: analysis is explicit and idempotent — the row is
        // already the stored audit result; re-running replays through the service.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // test accessors (used by FraudRingPanelTest; package-private on purpose)
    // ─────────────────────────────────────────────────────────────────────────

    /** Set in headless mode instead of a JDialog (no Window can exist). */
    JPanel lastDialogContent;
    FraudRingAnalysis currentAnalysis() { return current; }
    List<CustomerRow> parsedCustomers() { return customerRows; }
    List<GroupRow> parsedGroups() { return groupRows; }
    void simulateAnalysis(FraudRingAnalysis analysis) { showAnalysis(analysis); }
    void fireAnalyze() { runAnalysis(); }
    void showReasonsDialogForTest(int modelRow) {
        if (modelRow >= 0 && modelRow < customerRows.size()) showReasonsDialog(customerRows.get(modelRow));
    }
    void showGroupReasonsDialogForTest(int modelRow) {
        if (modelRow >= 0 && modelRow < groupRows.size()) showReasonsDialog(groupRows.get(modelRow));
    }
    JPanel lastDialogContentForTest() { return lastDialogContent; }
    JTable customersTable() { return customerTable; }
    JTable groupsTable() { return groupTable; }
    JComboBox<LookbackOption> lookback() { return lookbackCombo; }
    JCheckBox sharedPhone() { return sharedPhoneCheck; }
    JRadioButton singleScope() { return scopeSingleRadio; }
    JSpinner scopeId() { return scopeIdSpinner; }
    JButton analyze() { return analyzeButton; }
    JLabel status() { return statusLabel; }
    JLabel summary() { return summaryLabel; }
}
