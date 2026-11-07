package com.dujiteng.ragentlearning.chat.stream;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 当前实例正在执行的流；不宣称具备跨节点取消能力。 */
@Component
public class ChatStreamRegistry {
    private final Map<String, Running> streams = new ConcurrentHashMap<>();

    public String register(long userId, String conversationId, StreamCancellation cancellation, Runnable stop) {
        String id = UUID.randomUUID().toString();
        streams.put(id, new Running(userId, conversationId, cancellation, stop));
        return id;
    }

    public void remove(String id) { streams.remove(id); }

    public CancelResult cancel(long userId, String conversationId, String id) {
        var task = streams.get(id);
        if (task == null) return new CancelResult(false, "NOT_RUNNING");
        if (task.userId() != userId || !task.conversationId().equals(conversationId)) {
            throw new ChatApiException(404, "STREAM_NOT_FOUND", "流式任务不存在或不属于当前用户");
        }
        task.stop().run();
        return task.cancellation().isCommitted() ? new CancelResult(false, "COMPLETED") : new CancelResult(true, "CANCELLED");
    }

    public void cancelConversation(long userId, String conversationId) {
        streams.values().stream().filter(task -> task.userId() == userId && task.conversationId().equals(conversationId))
                .forEach(task -> task.stop().run());
    }

    public record CancelResult(boolean cancelled, String status) { }
    private record Running(long userId, String conversationId, StreamCancellation cancellation, Runnable stop) { }
}
