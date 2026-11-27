package com.bank.view.admin;

import com.bank.model.Customer;
import com.bank.model.Message;
import com.bank.model.Message.SenderType;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.service.CustomerService;
import com.bank.service.MessageService;
import com.bank.view.DateUtil;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * InboxPanel — admin tab for the customer-message inbox (English, LTR).
 */
public class InboxPanel extends JPanel implements AdminFrame.Refreshable {

    private final AdminPrincipal admin;
    private final MessageService messageService;
    private final CustomerService customerService;

    private DefaultTableModel tableModel;
    private JTable table;
    private List<Message> allMessages;
    private JLabel unreadBadge;
    private JLabel statusLabel;

    public InboxPanel(AdminPrincipal admin,
            MessageService messageService, CustomerService customerService) {
        this.admin = AdminGuard.require(admin);
        this.messageService = messageService;
        this.customerService = customerService;
        setLayout(new BorderLayout(0, 8));
        setBackground(UITheme.SURFACE);
        setBorder(new EmptyBorder(12, 12, 12, 12));

        add(buildToolbar(),    BorderLayout.NORTH);
        add(buildTableCard(),  BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        refresh();
    }

    /** Two-row toolbar so buttons always render at full width. */
    private JPanel buildToolbar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setOpaque(false);

        // ── Row 1: unread badge ──────────────────────────────────────────────
        JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        badgeRow.setOpaque(false);
        badgeRow.setBorder(new EmptyBorder(0, 0, 8, 0));

        unreadBadge = new JLabel("\u25CF 0 unread");
        unreadBadge.setFont(UITheme.FONT_BUTTON);
        unreadBadge.setForeground(UITheme.DANGER);
        badgeRow.add(unreadBadge);

        // ── Row 2: actions ───────────────────────────────────────────────────
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.setBorder(new EmptyBorder(0, 0, 8, 0));

        JButton replyBtn   = UIUtils.successButton("Reply");
        JButton composeBtn = UIUtils.primaryButton("Compose");
        JButton refreshBtn = UIUtils.ghostButton("Refresh");

        refreshBtn.addActionListener(e -> refresh());
        replyBtn.addActionListener(e -> handleReply());
        composeBtn.addActionListener(e -> handleCompose());

        btnRow.add(replyBtn);
        btnRow.add(composeBtn);
        btnRow.add(refreshBtn);

        bar.add(badgeRow);
        bar.add(btnRow);
        return bar;
    }

    private JPanel buildTableCard() {
        JPanel card = UIUtils.cardPanel();
        card.setLayout(new BorderLayout(0, 8));
        card.add(UIUtils.subheading("Messages"), BorderLayout.NORTH);

        String[] cols = {"ID", "From", "E-mail", "Subject", "Date", "Status"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        table = new JTable(tableModel);
        UIUtils.styleTable(table);

        table.setFont(UITheme.FONT_BODY);
        table.setRowHeight(28);
        table.setGridColor(UITheme.BORDER);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(UITheme.SELECTION_BG);

        int[] widths = {60, 140, 180, 200, 140, 100};
        for (int i = 0; i < widths.length; i++)
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                if (!sel) {
                    String status = String.valueOf(t.getValueAt(r, 5));
                    setBackground("Unread".equals(status)
                            ? new Color(0xE3, 0xF0, 0xFB)
                            : (r % 2 == 0 ? UITheme.CARD : UITheme.ROW_ALTERNATE));
                }
                setBorder(new EmptyBorder(0, 8, 0, 8));
                setHorizontalAlignment(SwingConstants.LEFT);
                return this;
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelectedMessage();
        });

        card.add(new JScrollPane(table), BorderLayout.CENTER);
        return card;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        bar.setOpaque(false);
        statusLabel = UIUtils.secondaryLabel("Select a message to view its content");
        bar.add(statusLabel);
        return bar;
    }

    @Override
    public void refresh() {
        new SwingWorker<List<Message>, Void>() {
            @Override protected List<Message> doInBackground() {
                return messageService.getAllMessages(admin);
            }
            @Override protected void done() {
                try {
                    allMessages = get();
                    populateTable();
                    updateUnreadBadge();
                } catch (InterruptedException | ExecutionException e) {
                    UIUtils.showError(InboxPanel.this, "Failed to load messages: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void populateTable() {
        tableModel.setRowCount(0);
        for (Message m : allMessages) {
            String senderName = "Bank Admin";
            String senderEmail = "—";

            if (m.getSenderType() == SenderType.CUSTOMER) {
                try {
                    Customer c = customerService.findCustomerById(admin, m.getCustomerId());
                    senderName = c.getFullName();
                    senderEmail = c.getEmail();
                } catch (Exception e) {
                    senderName = "Customer #" + m.getCustomerId();
                }
            }

            String status = m.isRead() ? "Read" : "Unread";

            // keep the ID as a plain English number
            tableModel.addRow(new Object[]{
                m.getMessageId(),
                senderName,
                senderEmail,
                m.getSubject(),
                m.getCreatedAt() != null ? DateUtil.formatDateTime(m.getCreatedAt()) : "—",
                status
            });
        }
        if (allMessages.isEmpty())
            tableModel.addRow(new Object[]{"—", "No messages exist", "—", "", "", ""});
        statusLabel.setText("Total: " + allMessages.size());
    }

    private void updateUnreadBadge() {
        int unread = messageService.getUnreadCount(admin);
        unreadBadge.setText((unread > 0 ? "\u25CF " : "\u25CB ") + unread + " unread");
        unreadBadge.setForeground(unread > 0 ? UITheme.DANGER : UITheme.ACCENT);
    }

    private String getCustomerName(long customerId) {
        try {
            Customer c = customerService.findCustomerById(admin, customerId);
            return c.getFullName() + " (" + c.getEmail() + ")";
        } catch (Exception e) {
            return "Customer #" + customerId;
        }
    }

    private void showSelectedMessage() {
        Message m = getSelectedMessage();
        if (m == null) {
            statusLabel.setText("Select a message to view its content");
            return;
        }
        if (!m.isRead() && m.getSenderType() == SenderType.CUSTOMER) {
            messageService.markAsRead(admin, m.getMessageId());
            m.setRead(true);
            populateTable();
            updateUnreadBadge();
        }

        String sender = m.getSenderType() == SenderType.CUSTOMER
                ? "Customer: " + getCustomerName(m.getCustomerId())
                : "Bank Admin";
        String details = "From: " + sender + "\n"
                       + "Subject: " + m.getSubject() + "\n"
                       + "Date: " + DateUtil.formatDateTime(m.getCreatedAt()) + "\n\n"
                       + m.getMessage();
        JTextArea area = new JTextArea(details);
        area.setEditable(false);
        area.setFont(UITheme.FONT_BODY);
        area.setBackground(UITheme.CARD);
        area.setBorder(new EmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(500, 300));
        JOptionPane.showMessageDialog(this, scroll, "Message Details", JOptionPane.PLAIN_MESSAGE);
    }

    private Message getSelectedMessage() {
        int row = table.getSelectedRow();
        if (row < 0 || allMessages == null) return null;

        Object idObj = tableModel.getValueAt(row, 0);
        if (idObj == null || idObj.toString().trim().isEmpty() || idObj.toString().equals("—")) {
            return null;
        }

        try {
            long id = Long.parseLong(idObj.toString().trim());
            return allMessages.stream()
                    .filter(m -> m.getMessageId() == id)
                    .findFirst().orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void handleReply() {
        Message m = getSelectedMessage();
        if (m == null) {
            UIUtils.showWarning(this, "Please select a message to reply to.");
            return;
        }
        if (m.getSenderType() == SenderType.ADMIN) {
            UIUtils.showWarning(this, "You cannot reply to an admin message.");
            return;
        }
        String subject = "Re: " + m.getSubject();
        JTextArea replyArea = new JTextArea(5, 30);
        replyArea.setLineWrap(true);
        replyArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(replyArea);
        int result = JOptionPane.showConfirmDialog(this, scroll,
                "Reply to " + getCustomerName(m.getCustomerId()),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        String content = replyArea.getText().trim();
        if (content.isEmpty()) {
            UIUtils.showWarning(this, "The message body cannot be empty.");
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                messageService.sendMessageFromAdmin(admin, m.getCustomerId(), subject, content);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(InboxPanel.this, "Reply sent.");
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(InboxPanel.this, "Sending the reply failed: " + ex.getCause().getMessage());
                } catch (InterruptedException ex) {
                    UIUtils.showError(InboxPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    private void handleCompose() {
        String email = UIUtils.inputDialog(this, "Enter the customer's e-mail address:", "New Message");
        if (email == null || email.trim().isEmpty()) return;
        Customer customer;
        try {
            customer = customerService.findCustomerByEmail(admin, email.trim());
        } catch (Exception ex) {
            UIUtils.showError(this, "No customer found with e-mail " + email + ".");
            return;
        }

        JTextField subjectField = UIUtils.styledField(30);
        JTextArea bodyArea = new JTextArea(5, 30);
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(bodyArea);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(4, 4, 4, 4);
        gc.weightx = 1.0;

        gc.gridy = 0;
        form.add(UIUtils.bodyLabel("To: " + customer.getFullName() + " (" + customer.getEmail() + ")"), gc);
        gc.gridy = 1;
        form.add(UIUtils.bodyLabel("Subject:"), gc);
        gc.gridy = 2;
        form.add(subjectField, gc);
        gc.gridy = 3;
        form.add(UIUtils.bodyLabel("Message:"), gc);
        gc.gridy = 4;
        gc.weighty = 1.0;
        gc.fill = GridBagConstraints.BOTH;
        form.add(scroll, gc);

        int res = JOptionPane.showConfirmDialog(this, form, "Send Message to Customer",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return;

        String subject = subjectField.getText().trim();
        String content = bodyArea.getText().trim();
        if (subject.isEmpty() || content.isEmpty()) {
            UIUtils.showWarning(this, "Subject and message body are required.");
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                messageService.sendMessageFromAdmin(admin, customer.getCustomerId(), subject, content);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(InboxPanel.this, "Message sent to " + customer.getFullName() + ".");
                    refresh();
                } catch (Exception ex) {
                    UIUtils.showError(InboxPanel.this, "Sending failed: " + ex.getMessage());
                }
            }
        }.execute();
    }
}
