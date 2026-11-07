package com.dujiteng.ragentlearning.infra.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class StreamCancellationTest {
    @Test
    void cancelledTurnCannotEnterSave() {
        var cancellation = new StreamCancellation();
        AtomicBoolean saved = new AtomicBoolean();
        assertTrue(cancellation.cancel());
        assertThrows(StreamCancellation.Cancelled.class, () -> cancellation.commit(() -> saved.set(true)));
        assertFalse(saved.get());
    }

    @Test
    void failedSaveDoesNotPretendToBeCommitted() {
        var cancellation = new StreamCancellation();
        assertThrows(IllegalStateException.class, () -> cancellation.commit(() -> { throw new IllegalStateException("回滚"); }));
        assertFalse(cancellation.isCommitted());
        assertTrue(cancellation.cancel());
    }

    @Test
    void cancellationAfterSaveStartedReportsCompleted() throws Exception {
        var cancellation = new StreamCancellation();
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var saving = executor.submit(() -> cancellation.commit(() -> {
                started.countDown();
                try { if (!finish.await(2, TimeUnit.SECONDS)) throw new AssertionError("保存未被释放"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var cancelling = executor.submit(cancellation::cancel);
            finish.countDown();
            saving.get(2, TimeUnit.SECONDS);
            assertFalse(cancelling.get(2, TimeUnit.SECONDS));
            assertTrue(cancellation.isCommitted());
            assertFalse(cancellation.isCancelled());
        } finally { finish.countDown(); executor.shutdownNow(); }
    }
}
