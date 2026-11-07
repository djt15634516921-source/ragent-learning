package com.dujiteng.ragentlearning.infra.llm;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 把下游断开/超时传给正在读取模型响应的线程。 */
public final class StreamCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<InputStream> upstream = new AtomicReference<>();
    private boolean committed;

    public void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new Cancelled();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void attach(InputStream body) {
        upstream.set(body);
        if (cancelled.get()) {
            close(upstream.getAndSet(null));
        }
        check();
    }

    public void detach(InputStream body) {
        upstream.compareAndSet(body, null);
    }

    public synchronized boolean cancel() {
        if (committed) return false;
        cancelled.set(true);
        close(upstream.getAndSet(null));
        return true;
    }

    /** 只包住完整回答的短事务；取消与提交在此确定先后，不包模型等待。 */
    public synchronized void commit(Runnable save) {
        check();
        save.run();
        committed = true;
    }

    public synchronized boolean isCommitted() {
        return committed;
    }

    private static void close(InputStream body) {
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // 已经在取消，不用关闭异常覆盖原来的结束原因。
            }
        }
    }

    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("流式请求已结束或取消");
        }
    }
}
