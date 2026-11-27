package com.bank.admin;

import com.bank.fraudring.application.FraudRingService;
import com.bank.fraudring.application.FraudRingServiceImpl;
import com.bank.fraudring.infrastructure.JdbcFraudRingResultRepository;
import com.bank.fraudring.infrastructure.JdbcFraudRingSourceRepository;
import com.bank.graph.application.CustomerGraphService;
import com.bank.graph.application.CustomerGraphServiceImpl;
import com.bank.graph.infrastructure.JdbcCustomerGraphRepository;
import com.bank.security.AdminAuthenticationService;
import com.bank.security.AdminPrincipal;
import com.bank.security.DatabaseAdminAuthenticationService;
import com.bank.security.LoginThrottle;
import com.bank.security.ThrottledAdminAuthenticationService;
import com.bank.fraud.outbox.FraudOutboxWorker;
import com.bank.fraud.outbox.JdbcFraudOutboxRepository;
import com.bank.service.ServiceFactory;

import java.time.Clock;

/**
 * Composition root for administrator-only components.
 *
 * <p>Deliberately separate from {@code ServiceFactory}: this class, {@code com.bank.security},
 * {@code com.bank.graph}, {@code com.bank.fraudring} and the admin views are excluded from
 * {@code CustomerBank.jar}, which must not carry administrator authentication or
 * investigation code.
 */
public final class AdminCompositionRoot {

    private static volatile AdminCompositionRoot instance;

    private final AdminAuthenticationService authenticationService;
    private final CustomerGraphService customerGraphService;
    private final FraudRingService fraudRingService;
    private final JdbcFraudOutboxRepository fraudOutboxRepository;
    private final FraudOutboxWorker fraudOutboxWorker;

    private AdminCompositionRoot() {
        // Brute-force protection wraps the credential check; see LoginThrottle for the policy and its limits.
        this.authenticationService = new ThrottledAdminAuthenticationService(
                new DatabaseAdminAuthenticationService(),
                new LoginThrottle(LoginThrottle.Policy.defaults(), Clock.systemUTC()));
        this.customerGraphService = new CustomerGraphServiceImpl(
                new JdbcCustomerGraphRepository(), Clock.systemDefaultZone());
        // Phase 2 — fraud-ring analysis: read-only snapshot source + idempotent result store.
        this.fraudRingService = new FraudRingServiceImpl(
                new JdbcFraudRingSourceRepository(),
                new JdbcFraudRingResultRepository(),
                Clock.systemDefaultZone());
        this.fraudOutboxRepository = new JdbcFraudOutboxRepository();
        this.fraudOutboxWorker = new FraudOutboxWorker(
                fraudOutboxRepository, ServiceFactory.getInstance().getFraudDetectionService());
    }

    public static AdminCompositionRoot getInstance() {
        if (instance == null) {
            synchronized (AdminCompositionRoot.class) {
                if (instance == null) instance = new AdminCompositionRoot();
            }
        }
        return instance;
    }

    public AdminAuthenticationService authenticationService() { return authenticationService; }
    public CustomerGraphService customerGraphService() { return customerGraphService; }
    public FraudRingService fraudRingService() { return fraudRingService; }
    /** Starts the durable event worker only after an administrator authenticates. */
    public void startFraudOutboxWorker() { fraudOutboxWorker.start(); }
    public void stopFraudOutboxWorker() { fraudOutboxWorker.close(); }
    public java.util.List<com.bank.fraud.outbox.FraudEventSummary> recentFraudEvents(AdminPrincipal admin, boolean failedOnly) {
        return fraudOutboxRepository.recentEvents(admin, failedOnly);
    }
    public void retryFailedFraudEvent(AdminPrincipal admin, long outboxId) {
        fraudOutboxRepository.retryFailed(admin, outboxId);
    }
}
