package io.github.kanabig.curtaincall.outbox.relay;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** 정리 작업처럼 가끔 도는 작업을 주기 실행한다. 실패해도 다음 주기에 다시 돈다. */
public class PeriodicTaskLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PeriodicTaskLifecycle.class);

    private final String name;
    private final Runnable task;
    private final Duration interval;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public PeriodicTaskLifecycle(String name, Runnable task, Duration interval) {
        this.name = name;
        this.task = task;
        this.interval = interval;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::runGuarded, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runGuarded() {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("{} 작업 실패, 다음 주기에 다시 시도한다", name, e);
        }
    }
}
