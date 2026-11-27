package com.bank.fraud.outbox;

import com.bank.service.FraudDetectionService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-process daemon worker. Events remain durable while the admin app is closed. */
public final class FraudOutboxWorker implements AutoCloseable {
    private final FraudOutboxRepository repository;
    private final FraudDetectionService fraudService;
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledExecutorService executor;
    private final Object processingLock = new Object();
    private volatile long generation;

    public FraudOutboxWorker(FraudOutboxRepository repository, FraudDetectionService fraudService) {
        this.repository = repository; this.fraudService = fraudService;
    }

    public boolean processOne() {
        var claimed = repository.claimNext();
        if (claimed.isEmpty()) return false;
        FraudOutboxEvent event = claimed.get();
        try {
            var result = fraudService.assessForOutbox(event.accountId(), event.customerId(),
                    event.transactionType(), event.amount(), event.balanceBefore(),
                    event.transactionId(), event.transferId(), event.occurredAt());
            repository.complete(event, result);
        } catch (RuntimeException failure) {
            repository.fail(event, failure);
        }
        return true;
    }

    public synchronized void start() {
        if (!started.compareAndSet(false,true)) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"fraud-outbox-worker"); t.setDaemon(true); return t; });
        long token = ++generation;
        executor.scheduleWithFixedDelay(() -> {
            synchronized (processingLock) {
                if (token != generation) return;
                try { processOne(); } catch (RuntimeException e) { System.err.println("[FraudOutboxWorker] " + e.getMessage()); }
            }
        },0,1, TimeUnit.SECONDS);
    }

    @Override public synchronized void close() { generation++; if (executor!=null) executor.shutdown(); started.set(false); }
}
