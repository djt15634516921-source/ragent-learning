package com.dujiteng.ragentlearning.chat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在有限的后台线程中读取模型流，不占住 Controller 的请求线程。 */
@Configuration
public class ChatStreamConfig {

    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdownNow")
    public ExecutorService chatStreamExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), runnable -> {
                    Thread thread = new Thread(runnable, "chat-stream-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}
