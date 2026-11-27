package com.bank.fraud.outbox;

import com.bank.model.FraudAlert;
import com.bank.model.Transaction.TransactionType;
import com.bank.security.AdminPrincipal;
import com.bank.service.FraudDetectionService;
import com.bank.service.FraudDetectionService.FraudAssessment;
import com.bank.service.FraudDetectionService.TriggeredRule;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Database-free tests for worker success, empty queue and retry-on-failure behavior. */
public final class FraudOutboxWorkerTest {
    private static int passed;
    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
    }
    public static void main(String[] args) {
        successUsesOriginalEventAndPersistsResult();
        emptyQueueDoesNothing();
        failureIsReturnedToRepositoryForRetry();
        System.out.println("FraudOutboxWorkerTest: " + passed + " passed, 0 failed");
    }
    private static FraudOutboxEvent event() {
        return new FraudOutboxEvent(42, 7, 9, TransactionType.DEPOSIT,
                new BigDecimal("12000.00"), new BigDecimal("1000.00"), 88L,
                null, LocalDateTime.of(2026, 11, 25, 10, 15), 1, 0);
    }
    private static void successUsesOriginalEventAndPersistsResult() {
        FakeRepository repo = new FakeRepository(event());
        FakeFraudService service = new FakeFraudService(false);
        FraudOutboxWorker worker = new FraudOutboxWorker(repo, service);
        check("worker claims one event", worker.processOne());
        check("original event timestamp passed to engine", event().occurredAt().equals(service.occurredAt));
        check("outbox identity preserved for atomic completion", repo.completedId == 42);
        check("assessment persisted", repo.completed && !repo.failed);
        check("completed event not processed twice", !worker.processOne());
    }
    private static void emptyQueueDoesNothing() {
        FakeRepository repo = new FakeRepository(null);
        FakeFraudService service = new FakeFraudService(false);
        FraudOutboxWorker worker = new FraudOutboxWorker(repo, service);
        check("empty queue returns false", !worker.processOne());
        check("empty queue does not call engine", service.calls == 0);
    }
    private static void failureIsReturnedToRepositoryForRetry() {
        FakeRepository repo = new FakeRepository(event());
        FakeFraudService service = new FakeFraudService(true);
        FraudOutboxWorker worker = new FraudOutboxWorker(repo, service);
        check("failed event was claimed", worker.processOne());
        check("failure is recorded for retry policy", repo.failed && !repo.completed);
    }
    private static final class FakeRepository implements FraudOutboxRepository {
        private FraudOutboxEvent next; boolean completed; boolean failed; long completedId;
        FakeRepository(FraudOutboxEvent next) { this.next=next; }
        public Optional<FraudOutboxEvent> claimNext() { FraudOutboxEvent v=next; next=null; return Optional.ofNullable(v); }
        public void complete(FraudOutboxEvent e, FraudAssessment a) { completed=true; completedId=e.outboxId(); }
        public void fail(FraudOutboxEvent e, Throwable t) { failed=true; }
        public void retryFailed(AdminPrincipal admin,long id) { throw new UnsupportedOperationException(); }
    }
    private static final class FakeFraudService implements FraudDetectionService {
        final boolean shouldFail; int calls; LocalDateTime occurredAt; 
        FakeFraudService(boolean shouldFail) { this.shouldFail=shouldFail; }
        public FraudAssessment evaluateAndRecord(long a,long c,TransactionType t,BigDecimal amount,BigDecimal before,Long tx,Long transfer) {
            throw new AssertionError("worker must use event-time overload");
        }
        @Override public FraudAssessment assessForOutbox(long a,long c,TransactionType t,BigDecimal amount,BigDecimal before,Long tx,Long transfer,LocalDateTime time) {
            calls++; occurredAt=time;
            if (shouldFail) throw new IllegalStateException("simulated engine outage");
            return new FraudAssessment(a,c,t,amount,0,FraudAlert.RiskLevel.LOW,List.<TriggeredRule>of());
        }
        public List<FraudAlert> getAllAlerts(AdminPrincipal admin) { return List.of(); }
        public List<FraudAlert> getAlertsByStatus(AdminPrincipal admin,FraudAlert.AlertStatus status) { return List.of(); }
        public void reviewAlert(AdminPrincipal admin,long id,FraudAlert.AlertStatus status,String note) { }
    }
}
