package io.github.kanabig.curtaincall.payment.config;

import io.github.kanabig.curtaincall.common.web.trace.RequestIdContext;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 작업을 제출한 스레드의 X-Request-Id를 실행 스레드의 MDC로 옮긴다. 가상 스레드는 MDC를 물려받지 않아 PG 호출 중의 로그와 그 결과로 쓰는 outbox 행에서 추적 ID가
 * 끊긴다.
 */
final class MdcPropagatingExecutor extends AbstractExecutorService {

    private final ExecutorService delegate;

    MdcPropagatingExecutor(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        String requestId = RequestIdContext.current();
        if (requestId == null) {
            delegate.execute(command);
            return;
        }
        delegate.execute(() -> {
            try (RequestIdContext.Scope ignored = RequestIdContext.open(requestId)) {
                command.run();
            }
        });
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
