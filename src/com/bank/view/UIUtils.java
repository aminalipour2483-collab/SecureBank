package com.bank.view;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * UIUtils — shared Swing factory helpers for the professional English (LTR)
 * theme. Every button gets a guaranteed minimum width so its label can never
 * be clipped (the old "Ban Use" truncation bug), tables are left-aligned with
 * dark navy headers, and all stock dialogs speak English.
 */
public final class UIUtils {

    private UIUtils() {}

    // ── Global Look & Feel + font defaults ───────────────────────────────────
    public static void applyLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        Font base = UITheme.FONT_BODY;
        UIManager.put("Button.font",              UITheme.FONT_BUTTON);
        UIManager.put("ToggleButton.font",        UITheme.FONT_BUTTON);
        UIManager.put("RadioButton.font",         base);
        UIManager.put("CheckBox.font",            base);
        UIManager.put("ColorChooser.font",        base);
        UIManager.put("ComboBox.font",            base);
        UIManager.put("Label.font",               base);
        UIManager.put("List.font",                base);
        UIManager.put("MenuBar.font",             base);
        UIManager.put("MenuItem.font",            base);
        UIManager.put("RadioButtonMenuItem.font", base);
        UIManager.put("CheckBoxMenuItem.font",    base);
        UIManager.put("Menu.font",                base);
        UIManager.put("PopupMenu.font",           base);
        UIManager.put("OptionPane.font",          base);
        UIManager.put("Panel.font",               base);
        UIManager.put("ProgressBar.font",         base);
        UIManager.put("ScrollPane.font",          base);
        UIManager.put("Viewport.font",            base);
        UIManager.put("TabbedPane.font",          UITheme.FONT_BUTTON);
        UIManager.put("Table.font",               base);
        UIManager.put("TableHeader.font",         UITheme.HEADER_FONT);
        UIManager.put("TextField.font",           base);
        UIManager.put("FormattedTextField.font",  base);
        UIManager.put("PasswordField.font",       base);
        UIManager.put("TextArea.font",            base);
        UIManager.put("TextPane.font",            base);
        UIManager.put("EditorPane.font",          base);
        UIManager.put("TitledBorder.font",        UITheme.FONT_SUBHEADING);
        UIManager.put("ToolBar.font",             base);
        UIManager.put("ToolTip.font",             UITheme.FONT_SMALL);
        UIManager.put("Tree.font",                base);
        UIManager.put("Spinner.font",             base);

        // English stock-dialog buttons
        UIManager.put("OptionPane.yesButtonText",     "Yes");
        UIManager.put("OptionPane.noButtonText",      "No");
        UIManager.put("OptionPane.okButtonText",      "OK");
        UIManager.put("OptionPane.cancelButtonText",  "Cancel");
    }

    /**
     * Forces left-to-right orientation on a whole window subtree. Call once on
     * every top-level frame/dialog after construction — guarantees a proper
     * English layout even on Windows installations with an RTL system locale.
     */
    public static void applyLTR(Container root) {
        root.applyComponentOrientation(java.awt.ComponentOrientation.LEFT_TO_RIGHT);
    }

    // ── Buttons ──────────────────────────────────────────────────────────────
    public static JButton primaryButton(String text) {
        return styledButton(text, UITheme.PRIMARY, UITheme.PRIMARY_DARK, UITheme.TEXT_ON_DARK);
    }
    public static JButton successButton(String text) {
        return styledButton(text, UITheme.ACCENT, UITheme.ACCENT_DARK, UITheme.TEXT_ON_DARK);
    }
    public static JButton dangerButton(String text) {
        return styledButton(text, UITheme.DANGER, UITheme.DANGER_DARK, UITheme.TEXT_ON_DARK);
    }
    public static JButton warningButton(String text) {
        return styledButton(text, UITheme.WARNING, UITheme.WARNING.darker(), UITheme.TEXT_ON_DARK);
    }
    public static JButton ghostButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(UITheme.FONT_BUTTON);
        btn.setForeground(UITheme.PRIMARY);
        btn.setBackground(UITheme.CARD);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER, 1),
                new EmptyBorder(7, 18, 7, 18)));
        Dimension pref = btn.getPreferredSize();
        int width = Math.max(pref.width, UITheme.BUTTON_MIN_WIDTH);
        btn.setPreferredSize(new Dimension(width, UITheme.BUTTON_HEIGHT));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { btn.setBackground(UITheme.SURFACE); }
            public void mouseExited(MouseEvent e)  { btn.setBackground(UITheme.CARD); }
        });
        return btn;
    }

    private static JButton styledButton(String text, Color bg, Color hover, Color fg) {
        JButton btn = new JButton(text);
        btn.setFont(UITheme.FONT_BUTTON);
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(7, 20, 7, 20));
        // Width: enough for the label + padding, but never below the minimum —
        // this is what guarantees "Ban User" etc. always render in full.
        Dimension pref = btn.getPreferredSize();
        int width = Math.max(pref.width, UITheme.BUTTON_MIN_WIDTH);
        btn.setPreferredSize(new Dimension(width, UITheme.BUTTON_HEIGHT));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { btn.setBackground(hover); }
            public void mouseExited(MouseEvent e)  { btn.setBackground(bg); }
        });
        return btn;
    }

    // ── Form fields ──────────────────────────────────────────────────────────
    public static JTextField styledField(int columns) {
        JTextField field = new JTextField(columns);
        field.setFont(UITheme.FONT_BODY);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER, 1),
                new EmptyBorder(6, 10, 6, 10)));
        field.setPreferredSize(new Dimension(field.getPreferredSize().width, UITheme.FIELD_HEIGHT));
        return field;
    }

    public static JPasswordField styledPasswordField(int columns) {
        JPasswordField field = new JPasswordField(columns);
        field.setFont(UITheme.FONT_BODY);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER, 1),
                new EmptyBorder(6, 10, 6, 10)));
        field.setPreferredSize(new Dimension(field.getPreferredSize().width, UITheme.FIELD_HEIGHT));
        return field;
    }

    // ── Labels ───────────────────────────────────────────────────────────────
    public static JLabel heading(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(UITheme.FONT_HEADING);
        lbl.setForeground(UITheme.TEXT_PRIMARY);
        return lbl;
    }
    public static JLabel subheading(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(UITheme.FONT_SUBHEADING);
        lbl.setForeground(UITheme.TEXT_PRIMARY);
        return lbl;
    }
    public static JLabel bodyLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(UITheme.FONT_BODY);
        lbl.setForeground(UITheme.TEXT_PRIMARY);
        return lbl;
    }
    public static JLabel secondaryLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(UITheme.FONT_SMALL);
        lbl.setForeground(UITheme.TEXT_SECONDARY);
        return lbl;
    }

    // ── Panels ───────────────────────────────────────────────────────────────
    public static JPanel cardPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(UITheme.CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER, 1),
                new EmptyBorder(UITheme.PADDING, UITheme.PADDING,
                                UITheme.PADDING, UITheme.PADDING)));
        return panel;
    }
    public static JPanel surfacePanel() {
        JPanel panel = new JPanel();
        panel.setBackground(UITheme.SURFACE);
        panel.setBorder(new EmptyBorder(UITheme.PADDING, UITheme.PADDING,
                                        UITheme.PADDING, UITheme.PADDING));
        return panel;
    }

    // ── Table styling ────────────────────────────────────────────────────────
    public static void styleTable(JTable table) {
        // OFF = each column keeps its preferred width (headers never squeeze);
        // a horizontal scrollbar appears when the table is wider than the view.
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setComponentOrientation(java.awt.ComponentOrientation.LEFT_TO_RIGHT);
        table.getTableHeader().setComponentOrientation(java.awt.ComponentOrientation.LEFT_TO_RIGHT);

        JTableHeader header = table.getTableHeader();
        header.setDefaultRenderer(new HeaderRenderer());
        header.setPreferredSize(new Dimension(header.getWidth(), 32));
        header.setReorderingAllowed(false);

        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            {
                setBorder(new EmptyBorder(4, 8, 4, 8));
                setHorizontalAlignment(SwingConstants.LEFT);   // LTR default
            }
        });
        table.setFillsViewportHeight(true);
    }

    public static void adjustColumnWidths(JTable table, int minWidth, int maxWidth) {
        for (int col = 0; col < table.getColumnCount(); col++) {
            TableColumn column = table.getColumnModel().getColumn(col);
            int contentWidth = getMaxContentWidth(table, col);
            int preferred = Math.max(minWidth, contentWidth + 16);
            if (maxWidth > 0 && preferred > maxWidth) preferred = maxWidth;
            column.setPreferredWidth(preferred);
        }
        table.getTableHeader().resizeAndRepaint();
        table.revalidate();
        table.repaint();
    }

    private static int getMaxContentWidth(JTable table, int col) {
        int max = 0;
        TableColumn column = table.getColumnModel().getColumn(col);
        String header = column.getHeaderValue().toString();
        FontMetrics fm = table.getFontMetrics(table.getTableHeader().getFont());
        max = Math.max(max, fm.stringWidth(header) + 12);
        for (int row = 0; row < table.getRowCount(); row++) {
            Object val = table.getValueAt(row, col);
            if (val != null) {
                String str = val.toString();
                fm = table.getFontMetrics(table.getFont());
                int w = fm.stringWidth(str) + 12;
                if (w > max) max = w;
            }
        }
        return max;
    }

    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBackground(UITheme.HEADER_BG);
            setForeground(UITheme.HEADER_FG);
            setFont(UITheme.HEADER_FONT);
            setHorizontalAlignment(SwingConstants.LEFT);   // LTR
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER),
                    new EmptyBorder(7, 8, 7, 8)));
            return this;
        }
    }

    // ── Stock dialogs (English titles) ───────────────────────────────────────
    public static void showError(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
    public static void showSuccess(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Success", JOptionPane.INFORMATION_MESSAGE);
    }
    public static void showWarning(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Warning", JOptionPane.WARNING_MESSAGE);
    }
    public static boolean confirmDialog(Component parent, String message) {
        int result = JOptionPane.showConfirmDialog(parent, message, "Confirm",
                JOptionPane.YES_NO_OPTION);
        return result == JOptionPane.YES_OPTION;
    }
    public static String inputDialog(Component parent, String message, String title) {
        return JOptionPane.showInputDialog(parent, message, title, JOptionPane.PLAIN_MESSAGE);
    }
}
