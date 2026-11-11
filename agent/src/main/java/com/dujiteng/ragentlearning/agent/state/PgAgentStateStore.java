package com.dujiteng.ragentlearning.agent.state;

import com.dujiteng.ragentlearning.agent.entity.AgentConversationDO;
import com.dujiteng.ragentlearning.agent.mapper.AgentStateMapper;
import com.dujiteng.ragentlearning.common.ChatApiException;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class PgAgentStateStore implements AgentStateStore {
    public static final String STATE_KEY = "agent_state";
    private final AgentStateMapper mapper;
    private final ConcurrentHashMap<Session, Turn> active = new ConcurrentHashMap<>();

    public PgAgentStateStore(AgentStateMapper mapper) {
        this.mapper = mapper;
    }

    public Turn open(AgentConversationDO conversation) {
        var session = new Session(conversation.userId(), conversation.id());
        String saved = mapper.payload(session.userId(), session.id(), STATE_KEY);
        // SDK 对加载异常会回退到新状态，先读取并验证，避免把已有会话静默重置。
        AgentState state;
        if (saved == null && conversation.revision() == 0) {
            state = AgentState.builder().sessionId(session.id()).userId(Long.toString(session.userId())).build();
        } else {
            try {
                state = saved == null ? null : AgentState.fromJsonString(saved);
            } catch (RuntimeException invalid) {
                throw unavailableState();
            }
            if (state == null || !session.id().equals(state.getSessionId())
                    || !Long.toString(session.userId()).equals(state.getUserId())) {
                throw unavailableState();
            }
        }
        var turn = new Turn(session, state);
        if (active.putIfAbsent(session, turn) != null) {
            throw new ChatApiException(409, "AGENT_CONVERSATION_BUSY", "这个 Agent 会话正在处理，请稍后重试");
        }
        return turn;
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        long owner = Long.parseLong(userId);
        if (STATE_KEY.equals(key)) {
            Turn turn = active.get(new Session(owner, sessionId));
            if (turn == null || turn.state != value) {
                throw new IllegalStateException("Agent 状态不属于当前运行");
            }
            // SDK 的回调只标记本轮状态可提交。真正的写库与展示消息使用同一个事务。
            turn.ready = true;
            return;
        }
        mapper.upsert(owner, sessionId, key, JsonUtils.getJsonCodec().toJson(value));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        mapper.upsert(Long.parseLong(userId), sessionId, key, JsonUtils.getJsonCodec().toJson(values));
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        long owner = Long.parseLong(userId);
        Turn turn = active.get(new Session(owner, sessionId));
        if (turn != null && STATE_KEY.equals(key) && type == AgentState.class) {
            return Optional.of(type.cast(turn.state));
        }
        String saved = mapper.payload(owner, sessionId, key);
        return saved == null ? Optional.empty() : Optional.of(JsonUtils.getJsonCodec().fromJson(saved, type));
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        String saved = userId == null ? null : mapper.payload(Long.parseLong(userId), sessionId, key);
        if (saved == null) {
            return List.of();
        }
        var codec = JsonUtils.getJsonCodec();
        List<?> values = codec.fromJson(saved, List.class);
        return values.stream().map(value -> codec.convertValue(value, itemType)).toList();
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return userId != null && mapper.exists(Long.parseLong(userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        if (userId != null) {
            mapper.deleteSession(Long.parseLong(userId), sessionId);
        }
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        if (userId != null) {
            mapper.deleteKey(Long.parseLong(userId), sessionId, key);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return userId == null ? Set.of() : new LinkedHashSet<>(mapper.listSessions(Long.parseLong(userId)));
    }

    private static ChatApiException unavailableState() {
        return new ChatApiException(503, "AGENT_STATE_UNAVAILABLE", "无法恢复这个会话的 Agent 状态");
    }

    private record Session(long userId, String id) {
    }

    public final class Turn implements AutoCloseable {
        private final Session session;
        private final AgentState state;
        private volatile boolean ready;

        private Turn(Session session, AgentState state) {
            this.session = session;
            this.state = state;
        }

        public String completedPayload(String visibleAnswer) {
            var context = state.contextMutable();
            if (!ready || context.isEmpty()) {
                throw unavailableState();
            }
            int lastIndex = context.size() - 1;
            var last = context.get(lastIndex);
            if (last.getRole() != MsgRole.ASSISTANT || last.hasContentBlocks(ToolUseBlock.class)) {
                throw unavailableState();
            }
            if (!visibleAnswer.equals(last.getTextContent())) {
                // 资料不足或迭代上限使用页面上的终答，后续追问也只继承这份终答。
                context.set(lastIndex, last.withContent(List.of(TextBlock.builder().text(visibleAnswer).build())));
            }
            return state.toJson();
        }

        @Override
        public void close() {
            active.remove(session, this);
        }
    }
}
