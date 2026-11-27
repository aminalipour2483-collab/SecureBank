package com.bank.view.admin;

import com.bank.admin.AdminCompositionRoot;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.service.AccountService;
import com.bank.service.CustomerService;
import com.bank.service.FraudDetectionService;
import com.bank.service.MessageService;
import com.bank.service.ServiceFactory;
import com.bank.service.TransferService;
import com.bank.view.AdminLoginFrame;
import com.bank.view.DateUtil;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDateTime;

/**
 * AdminFrame — administrator console main window (English, LTR).
 */
public class AdminFrame extends JFrame {

    private final AdminPrincipal        admin;
    private final CustomerService       customerService;
    private final AccountService        accountService;
    private final TransferService       transferService;
    private final MessageService        messageService;
    private final FraudDetectionService fraudDetectionService;   // v3.0

    private JLabel statusBarLabel;

    /** @throws com.bank.exception.AccessDeniedException if {@code admin} is null */
    public AdminFrame(AdminPrincipal admin) {
        this.admin                 = AdminGuard.require(admin);
        this.customerService       = ServiceFactory.getInstance().getCustomerService();
        this.accountService        = ServiceFactory.getInstance().getAccountService();
        this.transferService       = ServiceFactory.getInstance().getTransferService();
        this.messageService        = ServiceFactory.getInstance().getMessageService();
        this.fraudDetectionService = ServiceFactory.getInstance().getFraudDetectionService();   // v3.0
        initComponents();
        UIUtils.applyLTR(this);
    }

    private void initComponents() {
        setTitle(UITheme.APP_NAME + " — Admin Console");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                handleLogout();
            }
        });

        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(UITheme.SURFACE);

        add(buildHeader(),    BorderLayout.NORTH);
        add(buildTabs(),      BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        setMinimumSize(new Dimension(1024, 640));
        setPreferredSize(new Dimension(1180, 720));
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 0));
        header.setBackground(UITheme.PRIMARY_DARK);
        header.setBorder(new EmptyBorder(14, 22, 14, 22));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        JLabel title = new JLabel(UITheme.APP_NAME + " Admin Console");
        title.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 18));
        title.setForeground(UITheme.TEXT_ON_DARK);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel sub = new JLabel("Manage customers, accounts, fraud alerts and system data");
        sub.setFont(UITheme.FONT_SMALL);
        sub.setForeground(new Color(0x9A, 0xB4, 0xCC));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);

        left.add(title);
        left.add(Box.createVerticalStrut(3));
        left.add(sub);

        JButton logoutBtn = UIUtils.dangerButton("Sign Out");
        logoutBtn.addActionListener(e -> handleLogout());

        header.add(left,      BorderLayout.LINE_START);
        header.add(logoutBtn, BorderLayout.LINE_END);
        return header;
    }

    private JTabbedPane buildTabs() {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setFont(UITheme.FONT_BUTTON);
        tabs.setBackground(UITheme.SURFACE);
        tabs.setBorder(new EmptyBorder(8, 8, 8, 8));

        tabs.addTab("Customers",    new CustomerManagementPanel(admin, customerService, accountService));
        tabs.addTab("Accounts",     new AccountManagementPanel(admin, accountService, customerService));
        tabs.addTab("Transfers",    new TransferManagementPanel(admin, transferService));
        // v3.0 — Fraud Detection module
        tabs.addTab("Fraud Alerts", new FraudManagementPanel(admin, fraudDetectionService, customerService));
        tabs.addTab("Fraud Processing", new FraudProcessingPanel(admin));
        tabs.addTab("Fraud Rings", new FraudRingPanel(
                admin, AdminCompositionRoot.getInstance().fraudRingService()));
        tabs.addTab("Customer Graph", new CustomerGraphPanel(
                admin, AdminCompositionRoot.getInstance().customerGraphService(), customerService));
        tabs.addTab("Reports",      new ReportsPanel(admin, customerService, accountService, transferService));
        tabs.addTab("Inbox",        new InboxPanel(admin, messageService, customerService));

        tabs.addChangeListener(e -> {
            Component selected = tabs.getSelectedComponent();
            if (selected instanceof Refreshable) {
                ((Refreshable) selected).refresh();
            }
            updateStatusBar("Now viewing: " + tabs.getTitleAt(tabs.getSelectedIndex()).trim());
        });

        return tabs;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(0xE9, 0xED, 0xF2));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.BORDER),
                new EmptyBorder(4, 14, 4, 14)));

        statusBarLabel = new JLabel("Administrator session active");
        statusBarLabel.setFont(UITheme.FONT_SMALL);
        statusBarLabel.setForeground(UITheme.TEXT_SECONDARY);

        String time = DateUtil.formatDateTime(LocalDateTime.now());
        JLabel timeLbl = new JLabel("Session start: " + time);
        timeLbl.setFont(UITheme.FONT_SMALL);
        timeLbl.setForeground(UITheme.TEXT_SECONDARY);

        bar.add(statusBarLabel, BorderLayout.LINE_START);
        bar.add(timeLbl,        BorderLayout.LINE_END);
        return bar;
    }

    private void handleLogout() {
        boolean confirmed = UIUtils.confirmDialog(this, "Sign out of the admin console?");
        if (!confirmed) return;
        AdminCompositionRoot.getInstance().stopFraudOutboxWorker();
        AdminLoginFrame login = new AdminLoginFrame();
        login.setVisible(true);
        dispose();
    }

    public void updateStatusBar(String message) {
        if (statusBarLabel != null) statusBarLabel.setText(message);
    }

    public interface Refreshable {
        void refresh();
    }
}
