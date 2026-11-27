package com.bank.view.dialog;

import com.bank.model.Message;
import com.bank.model.Message.SenderType;
import com.bank.service.MessageService;
import com.bank.security.CustomerPrincipal;
import com.bank.security.CustomerSessionService;
import com.bank.view.DateUtil;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Customer-facing inbox. Every operation is scoped by the current authenticated customer principal. */
public final class CustomerInboxDialog extends JDialog {
    private final MessageService messageService;
    private final CustomerSessionService sessionService;
    private final CustomerPrincipal principal;
    private List<Message> messages = List.of();
    private DefaultTableModel tableModel;
    private JTable table;
    private JTextArea detailArea;
    private JLabel statusLabel;
    private JLabel unreadLabel;
    private JButton openButton;
    private JButton replyButton;

    public CustomerInboxDialog(JFrame owner, MessageService messageService,
                               CustomerSessionService sessionService, CustomerPrincipal principal) {
        super(owner, "SecureBank — Inbox", true);
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.principal = principal;
        setLayout(new BorderLayout(0, 8));
        getContentPane().setBackground(UITheme.SURFACE);
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(UITheme.SURFACE);
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        root.add(buildToolbar(), BorderLayout.NORTH);
        root.add(buildContent(), BorderLayout.CENTER);
        root.add(buildStatus(), BorderLayout.SOUTH);
        setContentPane(root);
        setMinimumSize(new Dimension(760, 560));
        pack();
        setLocationRelativeTo(owner);
        UIUtils.applyLTR(this);
        refresh();
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        unreadLabel = UIUtils.secondaryLabel("Unread from bank: …");
        left.add(unreadLabel);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        JButton compose = UIUtils.primaryButton("New Message");
        compose.addActionListener(e -> composeMessage());
        JButton refresh = UIUtils.ghostButton("Refresh");
        refresh.addActionListener(e -> refresh());
        openButton = UIUtils.ghostButton("Open");
        openButton.setEnabled(false);
        openButton.addActionListener(e -> openSelected());
        replyButton = UIUtils.successButton("Reply");
        replyButton.setEnabled(false);
        replyButton.addActionListener(e -> replyToSelected());
        JButton close = UIUtils.ghostButton("Close");
        close.addActionListener(e -> dispose());
        right.add(compose); right.add(openButton); right.add(replyButton); right.add(refresh); right.add(close);
        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JSplitPane buildContent() {
        JPanel tableCard = UIUtils.cardPanel();
        tableCard.setLayout(new BorderLayout(0, 6));
        tableCard.add(UIUtils.subheading("Messages"), BorderLayout.NORTH);
        tableModel = new DefaultTableModel(new String[]{"Date", "From", "Subject", "Status"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        table = new JTable(tableModel);
        UIUtils.styleTable(table);
        table.setRowHeight(27);
        int[] widths = {145, 110, 280, 120};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateSelection();
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) openSelected(); }
        });
        tableCard.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel detailCard = UIUtils.cardPanel();
        detailCard.setLayout(new BorderLayout(0, 6));
        detailCard.add(UIUtils.subheading("Message Details"), BorderLayout.NORTH);
        detailArea = new JTextArea();
        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setFont(UITheme.FONT_BODY);
        detailArea.setBackground(UITheme.CARD);
        detailArea.setBorder(new EmptyBorder(8, 8, 8, 8));
        detailArea.setText("Select a message and click Open, or double-click a row.");
        detailCard.add(new JScrollPane(detailArea), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableCard, detailCard);
        split.setResizeWeight(0.58);
        split.setBorder(null);
        return split;
    }

    private JPanel buildStatus() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        statusLabel = UIUtils.secondaryLabel("Your messages from the bank and your replies appear here.");
        bar.add(statusLabel, BorderLayout.WEST);
        return bar;
    }

    private void refresh() {
        statusLabel.setText("Loading messages...");
        new SwingWorker<List<Message>, Void>() {
            @Override protected List<Message> doInBackground() { sessionService.requireActiveAccount(principal); return messageService.getMessagesForCustomer(principal); }
            @Override protected void done() {
                try {
                    messages = get();
                    populateTable();
                    refreshUnreadCount();
                    statusLabel.setText(messages.size() + " message(s).");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Loading was interrupted.");
                } catch (ExecutionException e) {
                    statusLabel.setText("Could not load messages: " + safeMessage(e.getCause()));
                }
            }
        }.execute();
    }

    private void populateTable() {
        tableModel.setRowCount(0);
        for (Message m : messages) {
            String from = m.getSenderType() == SenderType.ADMIN ? "Bank Admin" : "You";
            String date = m.getCreatedAt() == null ? "—" : DateUtil.formatDateTime(m.getCreatedAt());
            String read;
            if (m.getSenderType() == SenderType.ADMIN) read = m.isRead() ? "Read" : "New";
            else read = m.isRead() ? "Read by bank" : "Sent";
            tableModel.addRow(new Object[]{date, from, m.getSubject(), read});
        }
        if (messages.isEmpty()) tableModel.addRow(new Object[]{"—", "No messages yet", "", ""});
        updateSelection();
    }

    private Message selectedMessage() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= messages.size()) return null;
        return messages.get(row);
    }

    private void updateSelection() {
        Message m = selectedMessage();
        openButton.setEnabled(m != null);
        replyButton.setEnabled(m != null && m.getSenderType() == SenderType.ADMIN);
    }

    private void openSelected() {
        Message selected = selectedMessage();
        if (selected == null) return;
        openButton.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                sessionService.requireActiveAccount(principal);
                if (selected.getSenderType() == SenderType.ADMIN && !selected.isRead()) {
                    messageService.markAsReadByCustomer(principal, selected.getMessageId());
                }
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    if (selected.getSenderType() == SenderType.ADMIN) selected.setRead(true);
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        String status = selected.getSenderType() == SenderType.ADMIN
                                ? (selected.isRead() ? "Read" : "New")
                                : (selected.isRead() ? "Read by bank" : "Sent");
                        tableModel.setValueAt(status, row, 3);
                    }
                    String date = selected.getCreatedAt() == null ? "—" : DateUtil.formatDateTime(selected.getCreatedAt());
                    detailArea.setText("From: " + (selected.getSenderType() == SenderType.ADMIN ? "Bank Admin" : "You")
                            + "\nSubject: " + selected.getSubject() + "\nDate: " + date + "\n\n" + selected.getMessage());
                    refreshUnreadCount();
                    updateSelection();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Opening the message was interrupted.");
                } catch (ExecutionException e) {
                    statusLabel.setText("Could not open message: " + safeMessage(e.getCause()));
                    updateSelection();
                }
            }
        }.execute();
    }

    private void replyToSelected() {
        Message selected = selectedMessage();
        if (selected == null || selected.getSenderType() != SenderType.ADMIN) return;
        JTextArea body = new JTextArea(6, 36);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        int result = JOptionPane.showConfirmDialog(this, new JScrollPane(body),
                "Reply to Bank Admin", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        sendMessage("Re: " + selected.getSubject(), body.getText().trim());
    }

    private void composeMessage() {
        JTextField subject = UIUtils.styledField(30);
        JTextArea body = new JTextArea(6, 36);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1; gc.insets = new Insets(5, 5, 5, 5);
        gc.gridy = 0; form.add(UIUtils.bodyLabel("Subject"), gc);
        gc.gridy = 1; form.add(subject, gc);
        gc.gridy = 2; form.add(UIUtils.bodyLabel("Message"), gc);
        gc.gridy = 3; gc.weighty = 1; gc.fill = GridBagConstraints.BOTH; form.add(new JScrollPane(body), gc);
        int result = JOptionPane.showConfirmDialog(this, form, "New Message to Bank Admin",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) sendMessage(subject.getText().trim(), body.getText().trim());
    }

    private void sendMessage(String subject, String content) {
        if (subject == null || subject.isBlank() || content == null || content.isBlank()) {
            UIUtils.showWarning(this, "Subject and message body are required.");
            return;
        }
        setActionsEnabled(false);
        new SwingWorker<Long, Void>() {
            @Override protected Long doInBackground() { sessionService.requireActiveAccount(principal); return messageService.sendMessageFromCustomer(principal, subject, content); }
            @Override protected void done() {
                setActionsEnabled(true);
                try {
                    get();
                    UIUtils.showSuccess(CustomerInboxDialog.this, "Your message has been sent to the bank administration.");
                    detailArea.setText("Message sent. The bank administration can now reply to you.");
                    refresh();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    UIUtils.showError(CustomerInboxDialog.this, "Sending was interrupted.");
                } catch (ExecutionException e) {
                    UIUtils.showError(CustomerInboxDialog.this, "Sending failed: " + safeMessage(e.getCause()));
                }
            }
        }.execute();
    }

    private void refreshUnreadCount() {
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() { sessionService.requireActiveAccount(principal); return messageService.getUnreadCountForCustomer(principal); }
            @Override protected void done() {
                try { unreadLabel.setText("Unread from bank: " + get()); }
                catch (Exception e) { unreadLabel.setText("Unread count unavailable"); }
            }
        }.execute();
    }

    private void setActionsEnabled(boolean enabled) {
        openButton.setEnabled(enabled && selectedMessage() != null);
        replyButton.setEnabled(enabled && selectedMessage() != null && selectedMessage().getSenderType() == SenderType.ADMIN);
    }

    private String safeMessage(Throwable t) { return t == null || t.getMessage() == null ? "Unexpected error" : t.getMessage(); }
}
