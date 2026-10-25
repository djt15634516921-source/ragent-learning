package com.dujiteng.ragentlearning.chat.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 本机学习版：按会话编号保存历史，应用重启后清空。 */
@Component
public class ConversationMemoryStore {

    // 一轮 = 一个 user 问题 + 一个 assistant 回答。
    private static final int MAX_TURNS = 6;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public Conversation getOrCreate(String conversationId) {
        return conversations.computeIfAbsent(conversationId, id -> new Conversation());
    }

    /** 一个编号对应一个 Conversation；也用它作为同一会话的锁。 */
    public static final class Conversation {

        private final List<Map<String, String>> messages = new ArrayList<>();

        public synchronized List<Map<String, String>> history() {
            // 返回快照，调用方无法直接修改已经保存的历史。
            return List.copyOf(messages);
        }

        public synchronized void appendTurn(String question, String answer) {
            // 两条消息一起准备好，再保存这一整轮。
            List<Map<String, String>> turn = List.of(
                    Map.of("role", "user", "content", question),
                    Map.of("role", "assistant", "content", answer));
            messages.addAll(turn);

            // 成对删除最早的问题和回答，保留最近六轮。
            while (messages.size() > MAX_TURNS * 2) {
                messages.subList(0, 2).clear();
            }
        }
    }
}
