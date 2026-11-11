package com.dujiteng.ragentlearning.agent.service.handler;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 会话内互斥覆盖模型执行和提交，数据库事务仅在最后写入时开启。 */
@Component
public class AgentRunGate {
    private final Set<Session> running = ConcurrentHashMap.newKeySet();

    public Permit acquire(long userId, String conversationId) {
        var session = new Session(userId, conversationId);
        if (!running.add(session)) {
            throw new ChatApiException(409, "AGENT_CONVERSATION_BUSY", "这个 Agent 会话正在处理，请稍后重试");
        }
        return () -> running.remove(session);
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private record Session(long userId, String conversationId) {
    }
}
