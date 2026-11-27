package com.bank.service;

import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.dao.FraudAlertDAO;
import com.bank.dao.MessageDAO;
import com.bank.dao.PinRecoveryDAO;
import com.bank.dao.RegistrationDAO;
import com.bank.dao.TransactionDAO;
import com.bank.dao.TransferDAO;
import com.bank.dao.impl.AccountDAOImpl;
import com.bank.dao.impl.CustomerDAOImpl;
import com.bank.dao.impl.FraudAlertDAOImpl;
import com.bank.dao.impl.MessageDAOImpl;
import com.bank.dao.impl.PinRecoveryDAOImpl;
import com.bank.dao.impl.RegistrationDAOImpl;
import com.bank.dao.impl.TransactionDAOImpl;
import com.bank.dao.impl.TransferDAOImpl;
import com.bank.service.impl.AccountServiceImpl;
import com.bank.service.impl.CustomerServiceImpl;
import com.bank.service.impl.FraudDetectionServiceImpl;
import com.bank.service.impl.MessageServiceImpl;
import com.bank.service.impl.RegistrationServiceImpl;
import com.bank.service.impl.TransferServiceImpl;
import com.bank.security.CustomerSessionContext;
import com.bank.security.CustomerAccessGuard;
import com.bank.security.CustomerSessionService;

/**
 * ServiceFactory — creates and wires all DAOs and Services.
 *
 * Folder: BankManagementSystem/src/com/bank/service/ServiceFactory.java
 *
 * WHY THIS CLASS EXISTS:
 * Every Swing panel (View) needs a Service to call. Without a factory,
 * each panel would have to manually create DAOs and Services, repeating
 * the wiring code everywhere.
 *
 * Instead, every View does this single line:
 *   AccountService accountService = ServiceFactory.getInstance().getAccountService();
 *
 * This is a simplified Service Locator — it's beginner-friendly and works
 * perfectly for a single-process Swing application.
 *
 * HOW TO USE FROM A SWING PANEL:
 *   // At the top of your JPanel or JFrame constructor:
 *   private final AccountService  accountService  = ServiceFactory.getInstance().getAccountService();
 *   private final TransferService transferService = ServiceFactory.getInstance().getTransferService();
 *   private final CustomerService customerService = ServiceFactory.getInstance().getCustomerService();
 *   private final MessageService  messageService  = ServiceFactory.getInstance().getMessageService();
 *   private final FraudDetectionService fraudService =
 *           ServiceFactory.getInstance().getFraudDetectionService();   // ← v3.0
 */
public class ServiceFactory {

    // ── Singleton instance ────────────────────────────────────────────────────
    private static volatile ServiceFactory instance;

    // ── DAOs (created once, shared) ───────────────────────────────────────────
    private final CustomerDAO    customerDAO;
    private final AccountDAO     accountDAO;
    private final TransactionDAO transactionDAO;
    private final TransferDAO    transferDAO;
    private final MessageDAO     messageDAO;
    private final FraudAlertDAO  fraudAlertDAO;   // ← v3.0 NEW
    private final RegistrationDAO registrationDAO;

    // ── Services (created once, shared) ───────────────────────────────────────
    private final CustomerService        customerService;
    private final AccountService         accountService;
    private final TransferService        transferService;
    private final MessageService         messageService;
    private final FraudDetectionService  fraudDetectionService;   // ← v3.0 NEW
    private final CustomerSessionService customerSessionService;
    private final RegistrationService registrationService;
    private final CustomerAccessGuard customerAccessGuard;

    // ── Private constructor — wires everything together ───────────────────────
    private ServiceFactory() {
        // 1. Instantiate DAOs
        this.customerDAO    = new CustomerDAOImpl();
        this.accountDAO     = new AccountDAOImpl();
        this.transactionDAO = new TransactionDAOImpl();
        this.transferDAO    = new TransferDAOImpl();
        this.messageDAO     = new MessageDAOImpl();
        PinRecoveryDAO pinRecoveryDAO = new PinRecoveryDAOImpl();
        this.fraudAlertDAO  = new FraudAlertDAOImpl();   // ← v3.0 NEW
        this.registrationDAO = new RegistrationDAOImpl();

        // 2. Instantiate the fraud engine FIRST — the money-movement
        //    services depend on it.                               ← v3.0 NEW
        this.fraudDetectionService = new FraudDetectionServiceImpl(
                fraudAlertDAO, transactionDAO, accountDAO);

        // 3. Establish the customer authorization guard at the application composition root.
        CustomerSessionContext customerSessionContext = CustomerSessionContext.getInstance();
        this.customerAccessGuard = new CustomerAccessGuard(
                accountDAO, customerDAO, customerSessionContext);

        // 4. Inject DAOs and the same guard into customer-facing services.
        this.customerService = new CustomerServiceImpl(customerDAO, accountDAO, customerAccessGuard);
        this.accountService = new AccountServiceImpl(
                accountDAO, customerDAO, transactionDAO, fraudDetectionService,
                pinRecoveryDAO, customerAccessGuard);
        this.transferService = new TransferServiceImpl(
                transferDAO, accountDAO, customerDAO, fraudDetectionService, customerAccessGuard);
        this.messageService  = new MessageServiceImpl(messageDAO, customerDAO, customerAccessGuard);
        this.customerSessionService = new CustomerSessionService(
                accountService, accountDAO, customerDAO, customerSessionContext);
        this.registrationService = new RegistrationServiceImpl(registrationDAO);
    }

    // ── Public accessor (thread-safe double-checked locking) ─────────────────
    public static ServiceFactory getInstance() {
        if (instance == null) {
            synchronized (ServiceFactory.class) {
                if (instance == null) {
                    instance = new ServiceFactory();
                }
            }
        }
        return instance;
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public CustomerService        getCustomerService()        { return customerService; }
    public AccountService         getAccountService()         { return accountService; }
    public TransferService        getTransferService()        { return transferService; }
    public MessageService         getMessageService()         { return messageService; }
    public FraudDetectionService  getFraudDetectionService()  { return fraudDetectionService; }   // ← v3.0 NEW
    public CustomerSessionService getCustomerSessionService() { return customerSessionService; }
    public RegistrationService getRegistrationService() { return registrationService; }
}