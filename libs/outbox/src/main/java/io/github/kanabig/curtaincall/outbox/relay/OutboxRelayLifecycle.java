package io.github.kanabig.curtaincall.outbox.relay;

import io.github.kanabig.curtaincall.outbox.config.OutboxProperties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** 릴레이 폴링과 정리 작업을 주기 실행한다. 서비스가 {@code @EnableScheduling}을 켜지 않아도 동작한다. */
public class OutboxRelayLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayLifecycle.class);

    private final OutboxRelay relay;
    private final OutboxMaintenance maintenance;
    private final OutboxProperties.Relay properties;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public OutboxRelayLifecycle(OutboxRelay relay, OutboxMaintenance maintenance, OutboxProperties.Relay properties) {
        this.relay = relay;
        this.maintenance = maintenance;
        this.properties = properties;
    }

    @Override
    public void start() {
        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                guarded(relay::pollOnce), 0, properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(
                guarded(maintenance::deleteExpired),
                properties.cleanupInterval().toMillis(),
                properties.cleanupInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(properties.sendTimeout().toMillis() * 2, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static Runnable guarded(Runnable task) {
        // 예외가 밖으로 나가면 scheduleWithFixedDelay가 이후 실행을 멈춘다
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warn("outbox 주기 작업 실패, 다음 주기에 다시 시도한다", e);
            }
        };
    }
}
