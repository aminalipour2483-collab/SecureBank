package com.bank.fraud.outbox;

import com.bank.service.FraudDetectionService.FraudAssessment;
import com.bank.security.AdminPrincipal;
import java.util.Optional;

public interface FraudOutboxRepository {
    Optional<FraudOutboxEvent> claimNext();
    void complete(FraudOutboxEvent event, FraudAssessment assessment);
    void fail(FraudOutboxEvent event, Throwable failure);
    void retryFailed(AdminPrincipal admin, long outboxId);
}
