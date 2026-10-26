package com.dujiteng.ragentlearning.chat;

import com.dujiteng.ragentlearning.chat.dto.ChatRequest;
import com.dujiteng.ragentlearning.chat.dto.ChatResponse;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** /chat 返回完整 JSON；/chat/stream 返回 start、delta、done 或 error 事件。 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final System.Logger LOG = System.getLogger(ChatController.class.getName());
    private final ChatService chatService;
    private final ExecutorService streamExecutor;

    public ChatController(ChatService chatService,
                          @Qualifier("chatStreamExecutor") ExecutorService streamExecutor) {
        this.chatService = chatService;
        this.streamExecutor = streamExecutor;
    }

    @PostMapping(value = "/chat", produces = "application/json;charset=UTF-8")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String id = request == null ? null : request.conversationId();
        String question = request == null ? null : request.question();
        return new ChatResponse(chatService.chat(id, question));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        // 参数错误在建立 SSE 前返回 HTTP 400 JSON。
        ChatRequest input = chatService.validateRequest(
                request == null ? null : request.conversationId(),
                request == null ? null : request.question());
        SseEmitter emitter = new SseEmitter(90_000L);
        StreamCancellation cancellation = new StreamCancellation();
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        Runnable cancel = () -> {
            cancellation.cancel();
            Future<?> current = task.get();
            if (current != null) {
                current.cancel(true);
            }
        };
        emitter.onTimeout(() -> {
            if (terminal.compareAndSet(false, true)) {
                cancel.run();
                emitter.complete();
            }
        });
        emitter.onError(error -> {
            terminal.set(true);
            cancel.run();
        });
        emitter.onCompletion(() -> {
            if (terminal.compareAndSet(false, true)) {
                cancel.run();
            }
        });

        try {
            Future<?> future = streamExecutor.submit(() -> {
                try {
                    send(emitter, cancellation, "start", Map.of("conversationId", input.conversationId()));
                    String answer = chatService.streamChat(input.conversationId(), input.question(), text -> {
                        if (terminal.get()) {
                            throw new StreamCancellation.Cancelled();
                        }
                        send(emitter, cancellation, "delta", Map.of("content", text));
                    }, cancellation);
                    cancellation.check();
                    if (terminal.compareAndSet(false, true)) {
                        send(emitter, cancellation, "done", Map.of("answer", answer));
                        emitter.complete();
                    }
                } catch (StreamCancellation.Cancelled ignored) {
                    // 断开和超时由容器回调收尾，不再发送第二个结束事件。
                } catch (ChatApiException error) {
                    fail(emitter, cancellation, terminal, error.code(), error.getMessage());
                } catch (RuntimeException error) {
                    LOG.log(System.Logger.Level.ERROR, "流式任务意外失败，异常类型：{0}", error.getClass().getName());
                    fail(emitter, cancellation, terminal, "CHAT_STREAM_FAILED", "流式处理失败，请检查后端运行情况");
                }
            });
            task.set(future);
            if (cancellation.isCancelled()) {
                future.cancel(true);
            }
        } catch (RejectedExecutionException error) {
            cancellation.cancel();
            emitter.complete();
            throw new ChatApiException(503, "CHAT_BUSY", "当前聊天任务较多，请稍后重试");
        }
        return emitter;
    }

    private static void send(SseEmitter emitter, StreamCancellation cancellation,
                             String event, Map<String, String> data) {
        cancellation.check();
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException error) {
            cancellation.cancel();
            throw new StreamCancellation.Cancelled();
        }
    }

    private static void fail(SseEmitter emitter, StreamCancellation cancellation,
                             AtomicBoolean terminal, String code, String message) {
        if (!cancellation.isCancelled() && terminal.compareAndSet(false, true)) {
            try {
                send(emitter, cancellation, "error", Map.of("code", code, "message", message));
                emitter.complete();
            } catch (StreamCancellation.Cancelled ignored) {
                // 发送错误事件时调用方也可能已经断开。
            }
        }
    }
}
