package com.bank.fraud;

import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.dao.FraudAlertDAO;
import com.bank.dao.TransactionDAO;
import com.bank.exception.CustomerBannedException;
import com.bank.exception.BankException;
import com.bank.model.Account;
import com.bank.model.BalanceChangeResult;
import com.bank.model.Account.AccountStatus;
import com.bank.model.Customer;
import com.bank.model.Customer.CustomerStatus;
import com.bank.model.FraudAlert;
import com.bank.model.FraudAlert.AlertStatus;
import com.bank.model.Transaction;
import com.bank.model.Transaction.TransactionType;
import com.bank.service.FraudDetectionService;
import com.bank.service.FraudDetectionService.FraudAssessment;
import com.bank.service.impl.AccountServiceImpl;
import com.bank.service.impl.CustomerServiceImpl;
import com.bank.service.impl.FraudDetectionServiceImpl;
import com.bank.util.BCryptUtil;
import com.bank.util.IdempotencyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FraudEngineSmokeTest — runnable WITHOUT a database.      (v3.0 — NEW)
 *
 * Folder: BankManagementSystem/test/com/bank/fraud/FraudEngineSmokeTest.java
 *
 * All DAOs are replaced by tiny in-memory fakes, so this exercises the
 * real business logic (scoring rules, alerts, ban/unban, login blocking)
 * with zero setup.
 */
public class FraudEngineSmokeTest {

    /** Fixed mid-day instant: results no longer depend on the hour the suite is run (ODD_HOUR is 00:00-05:00). */
    static final com.bank.security.AdminPrincipal ADMIN = com.bank.testkit.TestAdmin.principal();
    static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 14, 0);
    static final java.time.Clock CLOCK =
            java.time.Clock.fixed(NOW.toInstant(java.time.ZoneOffset.UTC), java.time.ZoneOffset.UTC);

    // =========================================================================
    // IN-MEMORY FAKES
    // =========================================================================

    static class FakeCustomerDAO implements CustomerDAO {
        final List<Customer> rows = new ArrayList<>();
        final AtomicLong seq = new AtomicLong(1);
        /** Lets the in-memory ban/unban mimic the real DAO's single transaction over both tables. */
        FakeAccountDAO accounts;

        @Override public long save(Customer c) {
            c.setCustomerId(seq.getAndIncrement());
            rows.add(c);
            return c.getCustomerId();
        }
        @Override public Optional<Customer> findById(long id) {
            return rows.stream().filter(c -> c.getCustomerId() == id).findFirst();
        }
        @Override public Optional<Customer> findByNationalId(String nid) {
            return rows.stream().filter(c -> c.getNationalId().equals(nid)).findFirst();
        }
        @Override public Optional<Customer> findByEmail(String email) {
            return rows.stream().filter(c -> c.getEmail().equals(email)).findFirst();
        }
        @Override public List<Customer> findAll() { return new ArrayList<>(rows); }
        @Override public List<Customer> searchByName(String kw) { return List.of(); }
        @Override public boolean update(Customer c) { return true; }
        @Override public boolean delete(long id) { return true; }

        @Override public boolean updateBanStatus(long id, CustomerStatus status, String reason) {
            Customer c = findById(id).orElseThrow();
            c.setStatus(status);
            c.setBannedReason(status == CustomerStatus.BANNED ? reason : null);
            c.setBannedAt(status == CustomerStatus.BANNED ? NOW : null);
            return true;
        }
        @Override public int banCustomerAndLockAccounts(long id, String reason) {
            Customer c = findById(id).orElseThrow();
            if (c.isBanned()) throw new BankException("Customer is already banned.");
            updateBanStatus(id, CustomerStatus.BANNED, reason);
            int locked = 0;
            if (accounts != null) {
                for (Account a : accounts.findByCustomerId(id)) {
                    if (a.getStatus() == AccountStatus.ACTIVE) {
                        a.setStatus(AccountStatus.LOCKED);
                        a.setLockReason(Account.LockReason.BAN);
                        locked++;
                    }
                }
            }
            return locked;
        }
        @Override public int unbanCustomerAndUnlockAccounts(long id) {
            Customer c = findById(id).orElseThrow();
            if (!c.isBanned()) throw new BankException("Customer is not banned.");
            updateBanStatus(id, CustomerStatus.ACTIVE, null);
            int unlocked = 0;
            if (accounts != null) {
                for (Account a : accounts.findByCustomerId(id)) {
                    // Unban deliberately leaves BAN locks for separate administrator review.
                    if (a.getLockReason() != Account.LockReason.BAN) {
                        a.setFailedPinAttempts(0);
                        a.setStatus(AccountStatus.ACTIVE);
                        unlocked++;
                    }
                }
            }
            return unlocked;
        }
        @Override public List<Customer> findAllBanned() {
            return rows.stream().filter(Customer::isBanned).toList();
        }
    }

    static class FakeAccountDAO implements AccountDAO {
        final List<Account> rows = new ArrayList<>();
        final AtomicLong seq = new AtomicLong(1);
        FakeTransactionDAO transactionLedger;
        final Map<String, String> idempotencyHashes = new HashMap<>();
        final Map<String, BalanceChangeResult> idempotencyResults = new HashMap<>();

        @Override public long save(Account a) {
            a.setAccountId(seq.getAndIncrement());
            rows.add(a);
            return a.getAccountId();
        }
        @Override public long saveWithOpeningDeposit(Account a) {
            return save(a);
        }
        @Override public Optional<Account> findById(long id) {
            return rows.stream().filter(a -> a.getAccountId() == id).findFirst();
        }
        @Override public Optional<Account> findByAccountNumber(String n) {
            return rows.stream().filter(a -> a.getAccountNumber().equals(n)).findFirst();
        }
        @Override public Optional<Account> findByCardNumber(String c) {
            return rows.stream().filter(a -> a.getCardNumber().equals(c)).findFirst();
        }
        @Override public List<Account> findByCustomerId(long cid) {
            return rows.stream().filter(a -> a.getCustomerId() == cid).toList();
        }
        @Override public List<Account> findAll() { return new ArrayList<>(rows); }
        @Override public BalanceChangeResult executeBalanceChange(
                long id, TransactionType type, BigDecimal amount, String idempotencyKey) {
            String hash = IdempotencyUtil.fingerprint(type.name(), Long.toString(id),
                    IdempotencyUtil.money(amount));
            if (idempotencyResults.containsKey(idempotencyKey)) {
                if (!hash.equals(idempotencyHashes.get(idempotencyKey))) {
                    throw new BankException("Idempotency key reused for a different request.");
                }
                BalanceChangeResult old = idempotencyResults.get(idempotencyKey);
                return new BalanceChangeResult(old.getCustomerId(), old.getBalanceBefore(),
                        old.getBalanceAfter(), old.getTransactionId(), true);
            }
            Account account = findById(id).orElseThrow();
            BigDecimal before = account.getBalance();
            BigDecimal after = type == TransactionType.DEPOSIT
                    ? before.add(amount) : before.subtract(amount);
            account.setBalance(after);
            Transaction tx = new Transaction(id, type, amount, after);
            long transactionId = transactionLedger == null
                    ? 1L : transactionLedger.save(tx);
            BalanceChangeResult result = new BalanceChangeResult(
                    account.getCustomerId(), before, after, transactionId);
            idempotencyHashes.put(idempotencyKey, hash);
            idempotencyResults.put(idempotencyKey, result);
            return result;
        }
        @Override public boolean updateStatus(long id, AccountStatus s) {
            findById(id).ifPresent(a -> { a.setStatus(s); a.setLockReason(s == AccountStatus.LOCKED
                    ? Account.LockReason.ADMIN : Account.LockReason.NONE); });
            return true;
        }
        @Override public boolean lockAfterFailedPinAttempts(long id) {
            findById(id).ifPresent(a -> { a.setStatus(AccountStatus.LOCKED); a.setLockReason(Account.LockReason.FAILED_PIN); });
            return true;
        }
        @Override public boolean resetPinAndUnlockAfterRecovery(long id, String hash) {
            findById(id).ifPresent(a -> { a.setPinHash(hash); a.setStatus(AccountStatus.ACTIVE);
                a.setLockReason(Account.LockReason.NONE); a.setFailedPinAttempts(0); });
            return true;
        }
        @Override public boolean updatePinHash(long id, String h) { return true; }
        @Override public int incrementFailedAttempts(long id) { return 0; }
        @Override public boolean resetFailedAttempts(long id) { return true; }
        @Override public boolean delete(long id) { return true; }
    }

    static class FakeTransactionDAO implements TransactionDAO {
        final List<Transaction> rows = new ArrayList<>();
        final AtomicLong seq = new AtomicLong(1);

        @Override public long save(Transaction t) {
            t.setTransactionId(seq.getAndIncrement());
            t.setCreatedAt(NOW);
            rows.add(t);
            return t.getTransactionId();
        }
        @Override public Optional<Transaction> findById(long id) {
            return rows.stream().filter(t -> t.getTransactionId() == id).findFirst();
        }
        @Override public List<Transaction> findByAccountId(long id) {
            return rows.stream().filter(t -> t.getAccountId() == id).toList();
        }
        @Override public List<Transaction> findRecentByAccountId(long id, int limit) {
            List<Transaction> list = new ArrayList<>(rows);
            list.removeIf(t -> t.getAccountId() != id);
            return list.size() <= limit ? list : list.subList(0, limit);
        }
        @Override public List<Transaction> findAll() { return new ArrayList<>(rows); }
    }

    static class FakeFraudAlertDAO implements FraudAlertDAO {
        final List<FraudAlert> rows = new ArrayList<>();
        final AtomicLong seq = new AtomicLong(1);

        @Override public long save(FraudAlert a) {
            a.setAlertId(seq.getAndIncrement());
            rows.add(a);
            return a.getAlertId();
        }
        @Override public Optional<FraudAlert> findById(long id) {
            return rows.stream().filter(a -> a.getAlertId() == id).findFirst();
        }
        @Override public List<FraudAlert> findAll() { return new ArrayList<>(rows); }
        @Override public List<FraudAlert> findByStatus(AlertStatus s) {
            return rows.stream().filter(a -> a.getStatus() == s).toList();
        }
        @Override public List<FraudAlert> findByCustomerId(long cid) {
            return rows.stream().filter(a -> a.getCustomerId() == cid).toList();
        }
        @Override public boolean updateReview(long id, AlertStatus s, String by, String note) {
            FraudAlert a = findById(id).orElse(null);
            if (a == null || a.getStatus() != AlertStatus.PENDING) return false;
            a.setStatus(s);
            a.setReviewedBy(by);
            a.setReviewedAt(NOW);
            a.setResolutionNote(note);
            return true;
        }
    }

    // =========================================================================
    // TEST RUNNER
    // =========================================================================
    private int passed = 0, failed = 0;

    private void check(String name, boolean condition, String context) {
        if (condition) {
            passed++;
            System.out.println("  ✔ " + name);
        } else {
            failed++;
            System.out.println("  ✘ " + name + "   [" + context + "]");
        }
    }

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" Fraud Detection Module — smoke test (no database required)");
        System.out.println("=================================================================");
        new FraudEngineSmokeTest().runAll();
    }

    void runAll() {
        testLargeAmountOnNewAccount();
        testStructuringAndAnomaly();
        testVelocityRule();
        testBanUnbanFlow();
        testLoginBlockedWhenBanned();
        testAlertReviewFlow();
        testDepositTriggersEngine();
        testBcryptPinHashing();
        testOddHourDependsOnInjectedClock();
        testOutboxReadOnlyScoring();

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================================================");
        if (failed > 0) System.exit(1);
    }

    void testOutboxReadOnlyScoring() {
        FakeFraudAlertDAO alerts = new FakeFraudAlertDAO();
        var engine = new FraudDetectionServiceImpl(alerts,new FakeTransactionDAO(),new FakeAccountDAO(),CLOCK);
        var score = engine.assessForOutbox(1,1,TransactionType.DEPOSIT,new BigDecimal("12000"),BigDecimal.ZERO,1L,null,NOW);
        check("outbox scoring is suspicious",score.getTotalScore()>=25,score.toString());
        check("outbox scoring creates no alert or alert id",alerts.rows.isEmpty() && !score.hasAlert(),score.toString());
    }

    // ── Scenario 1: large deposit on a brand-new account ─────────────────────
    void testLargeAmountOnNewAccount() {
        System.out.println("\n[1] Large deposit on a new account → LARGE_AMOUNT + NEW_ACCOUNT");
        FakeFraudAlertDAO alertDAO = new FakeFraudAlertDAO();
        FakeAccountDAO accountDAO = new FakeAccountDAO();
        FakeTransactionDAO txDAO = new FakeTransactionDAO();

        Account account = new Account(1, "100000000001", "401111111101",
                "1234", Account.AccountType.SAVINGS, new BigDecimal("3000"));
        account.setAccountId(1);
        account.setCreatedAt(NOW);        // brand-new
        accountDAO.rows.add(account);

        FraudDetectionService engine = new FraudDetectionServiceImpl(
                alertDAO, txDAO, accountDAO, CLOCK);

        FraudAssessment a = engine.evaluateAndRecord(
                1, 1, TransactionType.DEPOSIT,
                new BigDecimal("12000"), new BigDecimal("3000"), 1L, null);

        check("LARGE_AMOUNT rule fired", a.getRules().stream()
                .anyMatch(r -> r.getCode().equals("LARGE_AMOUNT")), a.toString());
        check("NEW_ACCOUNT rule fired", a.getRules().stream()
                .anyMatch(r -> r.getCode().equals("NEW_ACCOUNT")), a.toString());
        check("Score = 60 (40+20)", a.getTotalScore() == 60, "score=" + a.getTotalScore());
        check("Level = HIGH", a.getLevel() == FraudAlert.RiskLevel.HIGH, a.getLevel().toString());
        check("PENDING alert persisted", a.hasAlert()
                && alertDAO.rows.get(0).getStatus() == AlertStatus.PENDING,
                "alerts=" + alertDAO.rows.size());
        check("Rule codes joined", alertDAO.rows.get(0).getRuleCode()
                .equals("LARGE_AMOUNT+NEW_ACCOUNT"), alertDAO.rows.get(0).getRuleCode());
    }

    // ── Scenario 1b: ODD_HOUR is driven by the injected clock, not by when the suite runs ──
    void testOddHourDependsOnInjectedClock() {
        System.out.println("\n[9] ODD_HOUR rule follows the injected clock");
        for (int hour : new int[]{14, 2}) {
            FakeTransactionDAO txDAO = new FakeTransactionDAO();
            FakeAccountDAO accountDAO = new FakeAccountDAO();
            FakeFraudAlertDAO alertDAO = new FakeFraudAlertDAO();
            Account account = new Account(1, "100000000001", "401111111101",
                    "1234", Account.AccountType.SAVINGS, new BigDecimal("3000"));
            account.setAccountId(1);
            account.setCreatedAt(NOW.minusDays(400));
            accountDAO.rows.add(account);
            java.time.Clock clock = java.time.Clock.fixed(
                    NOW.withHour(hour).toInstant(java.time.ZoneOffset.UTC), java.time.ZoneOffset.UTC);
            FraudAssessment a = new FraudDetectionServiceImpl(alertDAO, txDAO, accountDAO, clock)
                    .evaluateAndRecord(1, 1, TransactionType.DEPOSIT,
                            new BigDecimal("12000"), new BigDecimal("3000"), 1L, null);
            boolean odd = a.getRules().stream().anyMatch(r -> r.getCode().equals("ODD_HOUR"));
            check("ODD_HOUR " + (hour == 2 ? "fires" : "does not fire") + " at " + hour + ":00",
                    odd == (hour == 2), a.toString());
        }
    }

    // ── Scenario 2: structuring withdrawal + statistical outlier ─────────────
    void testStructuringAndAnomaly() {
        System.out.println("\n[2] Withdrawal of 9,500 with small history → STRUCTURING + ANOMALOUS_AMOUNT");
        FakeTransactionDAO txDAO = new FakeTransactionDAO();
        FakeAccountDAO accountDAO = new FakeAccountDAO();
        FakeFraudAlertDAO alertDAO = new FakeFraudAlertDAO();

        Account account = new Account(2, "100000000002", "401111111102",
                "5678", Account.AccountType.CHECKING, new BigDecimal("10000"));
        account.setAccountId(2);
        account.setCreatedAt(NOW.minusDays(400));   // old account
        accountDAO.rows.add(account);

        // Ten small past transactions (mean ≈ 100, tiny σ) — a 9,500 withdrawal
        // is a massive z-score outlier on top of the structuring pattern.
        for (int i = 0; i < 10; i++) {
            Transaction past = new Transaction(2, TransactionType.WITHDRAW,
                    new BigDecimal("100"), new BigDecimal("9900"));
            past.setCreatedAt(NOW.minusHours(5 + i));
            txDAO.rows.add(past);
        }

        FraudDetectionService engine = new FraudDetectionServiceImpl(
                alertDAO, txDAO, accountDAO, CLOCK);
        FraudAssessment a = engine.evaluateAndRecord(
                2, 2, TransactionType.WITHDRAW,
                new BigDecimal("9500"), new BigDecimal("10000"), 11L, null);

        check("STRUCTURING rule fired", a.getRules().stream()
                .anyMatch(r -> r.getCode().equals("STRUCTURING")), a.toString());
        check("ANOMALOUS_AMOUNT (z-score) fired", a.getRules().stream()
                .anyMatch(r -> r.getCode().equals("ANOMALOUS_AMOUNT")), a.toString());
        check("BALANCE_DRAIN rule fired", a.getRules().stream()
                .anyMatch(r -> r.getCode().equals("BALANCE_DRAIN")), a.toString());
        check("Score = 75 → CRITICAL", a.getTotalScore() == 75
                && a.getLevel() == FraudAlert.RiskLevel.CRITICAL,
                "score=" + a.getTotalScore() + " level=" + a.getLevel());
    }

    // ── Scenario 3: velocity burst ────────────────────────────────────────────
    void testVelocityRule() {
        System.out.println("\n[3] Six transactions inside 10 minutes → HIGH_VELOCITY");
        FakeTransactionDAO txDAO = new FakeTransactionDAO();
        FakeAccountDAO accountDAO = new FakeAccountDAO();
        FakeFraudAlertDAO alertDAO = new FakeFraudAlertDAO();

        Account account = new Account(3, "100000000003", "401111111103",
                "9012", Account.AccountType.SAVINGS, new BigDecimal("500"));
        account.setAccountId(3);
        account.setCreatedAt(NOW.minusDays(400));
        accountDAO.rows.add(account);

        for (int i = 0; i < 6; i++) {
            Transaction past = new Transaction(3, TransactionType.WITHDRAW,
                    new BigDecimal("50"), new BigDecimal("450"));
            past.setCreatedAt(NOW.minusMinutes(2));
            txDAO.rows.add(past);
        }

        FraudDetectionService engine = new FraudDetectionServiceImpl(
                alertDAO, txDAO, accountDAO, CLOCK);
        FraudAssessment a = engine.evaluateAndRecord(
                3, 3, TransactionType.WITHDRAW,
                new BigDecimal("50"), new BigDecimal("500"), 7L, null);

        check("HIGH_VELOCITY rule fired", a.getRules().stream()
                .anyMatch(r -> r.getCode().equals("HIGH_VELOCITY")), a.toString());
    }

    // ── Scenario 4: ban / unban through CustomerService ──────────────────────
    void testBanUnbanFlow() {
        System.out.println("\n[4] banCustomer / unbanCustomer through the service layer");
        FakeCustomerDAO customerDAO = new FakeCustomerDAO();
        FakeAccountDAO accountDAO = new FakeAccountDAO();
        customerDAO.accounts = accountDAO;

        Customer dan = new Customer("NAT-004-DAN", "Dan", "Walker",
                java.time.LocalDate.of(1980, 9, 12), "+12025550404",
                "dan.walker@email.com", "Street 1", "12345", "Engineer");
        dan.setCustomerId(4);
        dan.setStatus(CustomerStatus.ACTIVE);
        customerDAO.rows.add(dan);

        Account acc = new Account(4, "100000000004", "401111111104",
                "2468", Account.AccountType.CHECKING, new BigDecimal("500"));
        acc.setAccountId(4);
        acc.setStatus(AccountStatus.ACTIVE);
        accountDAO.rows.add(acc);

        CustomerServiceImpl service = new CustomerServiceImpl(customerDAO, accountDAO);

        service.banCustomer(ADMIN, 4, "Confirmed fraud alert #3/#4");

        check("Customer status = BANNED", dan.isBanned(), dan.toString());
        check("Ban reason stored", "Confirmed fraud alert #3/#4".equals(dan.getBannedReason()),
                String.valueOf(dan.getBannedReason()));
        check("All accounts LOCKED", acc.getStatus() == AccountStatus.LOCKED,
                acc.toString());

        service.unbanCustomer(ADMIN, 4);

        check("Unban → ACTIVE again", dan.getStatus() == CustomerStatus.ACTIVE,
                dan.getStatus().toString());
        check("Unban → reason cleared", dan.getBannedReason() == null,
                String.valueOf(dan.getBannedReason()));
        check("Unban keeps ban-locked accounts LOCKED for separate admin review",
                acc.getStatus() == AccountStatus.LOCKED && acc.getLockReason() == Account.LockReason.BAN,
                acc.toString());
        check("getBannedCustomers empty after unban",
                service.getBannedCustomers(ADMIN).isEmpty(), "not empty");
    }

    // ── Scenario 5: banned customer cannot log in ─────────────────────────────
    void testLoginBlockedWhenBanned() {
        System.out.println("\n[5] Banned customer login → CustomerBannedException");
        FakeCustomerDAO customerDAO = new FakeCustomerDAO();
        FakeAccountDAO accountDAO = new FakeAccountDAO();
        customerDAO.accounts = accountDAO;
        FakeTransactionDAO txDAO = new FakeTransactionDAO();

        Customer eve = new Customer("NAT-005-EVE", "Eve", "Adams",
                java.time.LocalDate.of(1990, 1, 1), "+12025550505",
                "eve.adams@email.com", "Street 2", "67890", "Doctor");
        eve.setCustomerId(5);
        customerDAO.rows.add(eve);

        Account acc = new Account(5, "100000000005", "401111111105",
                BCryptUtil.hash("1111"), Account.AccountType.SAVINGS, new BigDecimal("1000"));
        acc.setAccountId(5);
        acc.setStatus(AccountStatus.ACTIVE);
        accountDAO.rows.add(acc);

        FraudDetectionService engine = new FraudDetectionServiceImpl(
                new FakeFraudAlertDAO(), txDAO, accountDAO, CLOCK);
        AccountServiceImpl accountService = new AccountServiceImpl(
                accountDAO, customerDAO, txDAO, engine);

        // Before the ban: login works.
        boolean loginOkBefore = false;
        try {
            accountService.login("401111111105", "1111");
            loginOkBefore = true;
        } catch (RuntimeException expected) { }

        check("Login works before ban", loginOkBefore, "unexpected exception");

        String currentHash = acc.getPinHash();
        acc.setPinHash("1111");
        boolean legacyRejected = false;
        try {
            accountService.login("401111111105", "1111");
        } catch (com.bank.exception.InvalidPinWithAttemptsException expected) {
            legacyRejected = true;
        }
        check("Legacy plaintext PIN rejected", legacyRejected, "plaintext login was accepted");
        acc.setPinHash(currentHash);

        // Ban, then try again.
        new CustomerServiceImpl(customerDAO, accountDAO)
                .banCustomer(ADMIN, 5, "Card testing pattern");

        String message = null;
        try {
            accountService.login("401111111105", "1111");
        } catch (CustomerBannedException e) {
            message = e.getMessage();
        }

        check("Login blocked after ban", message != null, "no exception thrown");
        check("Ban reason shown to customer",
                message != null && message.contains("Card testing pattern"),
                String.valueOf(message));
    }

    // ── Scenario 6: alert review lifecycle ────────────────────────────────────
    void testAlertReviewFlow() {
        System.out.println("\n[6] Alert review: PENDING → CONFIRMED, double-review rejected");
        FakeFraudAlertDAO alertDAO = new FakeFraudAlertDAO();
        FraudDetectionService engine = new FraudDetectionServiceImpl(
                alertDAO, new FakeTransactionDAO(), new FakeAccountDAO(), CLOCK);

        FraudAlert alert = new FraudAlert();
        alert.setCustomerId(1L);
        alert.setAccountId(1L);
        alert.setRuleCode("LARGE_AMOUNT");
        alert.setRuleDescription("1 fraud rule triggered");
        alert.setRiskScore(40);
        alert.setRiskLevel(FraudAlert.RiskLevel.MEDIUM);
        alert.setDetails("...");
        alert.setStatus(AlertStatus.PENDING);
        alertDAO.save(alert);

        engine.reviewAlert(ADMIN, 1, AlertStatus.CONFIRMED, "Confirmed — banned user.");

        check("Alert is CONFIRMED", alertDAO.findById(1L).orElseThrow()
                .getStatus() == AlertStatus.CONFIRMED, "wrong status");
        check("Reviewer recorded is the authenticated admin's username",
                com.bank.testkit.TestAdmin.USERNAME.equals(alertDAO.findById(1L).orElseThrow()
                .getReviewedBy()), "no reviewer");

        boolean doubleReviewRejected = false;
        try {
            engine.reviewAlert(ADMIN, 1, AlertStatus.DISMISSED, "changing my mind");
        } catch (RuntimeException e) {
            doubleReviewRejected = true;
        }
        check("Double-review rejected", doubleReviewRejected, "review applied twice!");
    }

    // ── Scenario 7: deposit service no longer runs fraud scoring inline ──────
    void testDepositTriggersEngine() {
        System.out.println("\n[7] AccountServiceImpl.deposit() → durable outbox owns fraud scoring");
        FakeCustomerDAO customerDAO = new FakeCustomerDAO();
        FakeAccountDAO accountDAO = new FakeAccountDAO();
        customerDAO.accounts = accountDAO;
        FakeTransactionDAO txDAO = new FakeTransactionDAO();
        accountDAO.transactionLedger = txDAO;
        FakeFraudAlertDAO alertDAO = new FakeFraudAlertDAO();

        Customer carol = new Customer("NAT-003-CAROL", "Carol", "Smith",
                java.time.LocalDate.of(1995, 7, 22), "+12025550303",
                "carol.smith@email.com", "Street 3", "11111", "Teacher");
        carol.setCustomerId(3);
        customerDAO.rows.add(carol);

        Account acc = new Account(3, "100000000003", "401111111103",
                "9012", Account.AccountType.SAVINGS, new BigDecimal("1000"));
        acc.setAccountId(3);
        acc.setStatus(AccountStatus.ACTIVE);
        acc.setCreatedAt(NOW);          // new account
        accountDAO.rows.add(acc);

        FraudDetectionService engine = new FraudDetectionServiceImpl(
                alertDAO, txDAO, accountDAO, CLOCK);
        AccountServiceImpl accountService = new AccountServiceImpl(
                accountDAO, customerDAO, txDAO, engine);

        // Simulate a lost response followed by a retry with the SAME client key.
        // The second call must replay the saved result and never post a second ledger row.
        String idempotencyKey = "test-deposit-001";
        accountService.deposit(3, new BigDecimal("9000"), idempotencyKey);
        accountService.deposit(3, new BigDecimal("9000"), idempotencyKey);

        check("Deposit posted once despite identical retry (balance updated)",
                acc.getBalance().compareTo(new BigDecimal("10000")) == 0,
                "balance=" + acc.getBalance());
        check("Ledger row written", txDAO.rows.size() == 1,
                "rows=" + txDAO.rows.size());
        check("fraud scoring is not executed inline on the financial request thread",
                alertDAO.rows.isEmpty(), "inline alerts=" + alertDAO.rows.size());

        boolean keyReuseConflict = false;
        try {
            accountService.deposit(3, new BigDecimal("9100"), idempotencyKey);
        } catch (BankException expected) {
            keyReuseConflict = true;
        }
        check("Same key with different amount is rejected", keyReuseConflict,
                "changed amount was accepted");
        check("Rejected key reuse did not change balance",
                acc.getBalance().compareTo(new BigDecimal("10000")) == 0,
                "balance=" + acc.getBalance());
    }
    // ── Scenario 8: PIN hashing and verification ─────────────────────────────
    void testBcryptPinHashing() {
        System.out.println("\n[8] PIN storage uses BCrypt rather than plaintext");
        String hash = BCryptUtil.hash("4826");
        check("BCrypt hash format recognized", BCryptUtil.isBcryptHash(hash), hash);
        check("Correct PIN verifies", BCryptUtil.verify("4826", hash), "correct PIN rejected");
        check("Incorrect PIN rejected", !BCryptUtil.verify("4827", hash), "wrong PIN accepted");
        check("Hash differs from plaintext", !"4826".equals(hash), "plaintext was stored");
    }

}
