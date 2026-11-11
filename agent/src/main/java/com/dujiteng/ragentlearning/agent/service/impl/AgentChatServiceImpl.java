package com.dujiteng.ragentlearning.agent.service.impl;

import com.dujiteng.ragentlearning.agent.config.ReActAgentProvider;
import com.dujiteng.ragentlearning.agent.repository.AgentConversationRepository;
import com.dujiteng.ragentlearning.agent.service.AgentChatService;
import com.dujiteng.ragentlearning.agent.service.AgentConversationService;
import com.dujiteng.ragentlearning.agent.service.handler.AgentRunContext;
import com.dujiteng.ragentlearning.agent.service.handler.AgentRunGate;
import com.dujiteng.ragentlearning.agent.state.PgAgentStateStore;
import com.dujiteng.ragentlearning.agent.vo.AgentChatResponseVO;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeRetrievalService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AgentChatServiceImpl implements AgentChatService {
    private static final System.Logger LOG = System.getLogger(AgentChatServiceImpl.class.getName());
    private final ReActAgentProvider agents;
    private final AgentConversationService conversations;
    private final AgentConversationRepository repository;
    private final PgAgentStateStore stateStore;
    private final AgentRunGate gate;
    private final KnowledgeRetrievalService retrieval;
    private final Duration timeout;

    public AgentChatServiceImpl(ReActAgentProvider agents, AgentConversationService conversations,
                                AgentConversationRepository repository, PgAgentStateStore stateStore,
                                AgentRunGate gate, KnowledgeRetrievalService retrieval,
                                @Value("${agent.timeout-ms:30000}") int timeoutMs) {
        if (timeoutMs < 1000 || timeoutMs > 90000) {
            throw new IllegalArgumentException("Agent 总超时须为 1000～90000 毫秒");
        }
        this.agents = agents;
        this.conversations = conversations;
        this.repository = repository;
        this.stateStore = stateStore;
        this.gate = gate;
        this.retrieval = retrieval;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public AgentChatResponseVO chat(long userId, long knowledgeBaseId, String conversationId, String question) {
        String normalized = question == null ? "" : question.strip();
        if (normalized.isEmpty() || normalized.length() > 8000) {
            throw new ChatApiException(400, "QUESTION_INVALID", "问题不能为空且最多 8000 字符");
        }
        if (knowledgeBaseId <= 0) {
            throw new ChatApiException(400, "KNOWLEDGE_BASE_ID_INVALID", "请选择有效知识库");
        }
        String id = conversationId == null
                ? conversations.create(userId, knowledgeBaseId, null).conversationId()
                : conversationId;
        try (var permit = gate.acquire(userId, id)) {
            var conversation = repository.requireOwned(userId, id);
            if (conversation.knowledgeBaseId() != knowledgeBaseId) {
                throw new ChatApiException(409, "AGENT_KNOWLEDGE_BASE_MISMATCH", "这个 Agent 会话绑定了另一个知识库");
            }
            retrieval.requireOwner(userId, knowledgeBaseId);
            var agent = agents.getAgent();
            var run = new AgentRunContext(userId, knowledgeBaseId, agents.maxIters());
            var context = RuntimeContext.builder()
                    .userId(Long.toString(userId))
                    .sessionId(id)
                    .put(AgentRunContext.class, run)
                    .build();
            try (var turn = stateStore.open(conversation)) {
                Msg reply = execute(agent, context, run, normalized);
                var result = response(id, reply, run);
                repository.appendTurn(conversation, normalized, result, turn.completedPayload(result.answer()));
                return result;
            } finally {
                run.cancellation().cancel();
                // 每轮从数据库取已提交版本，清理 SDK 缓存也会释放失败轮的可变状态。
                agent.clearStateCache(context);
            }
        }
    }

    private Msg execute(ReActAgent agent, RuntimeContext context, AgentRunContext run, String question) {
        try {
            return agent.streamEvents(question, context)
                    .doOnNext(event -> {
                        if (event instanceof ToolResultEndEvent tool && tool.getState() != ToolResultState.SUCCESS) {
                            run.recordFrameworkRejection(tool.getToolCallId(), tool.getToolCallName());
                        }
                    })
                    .ofType(AgentResultEvent.class)
                    .map(AgentResultEvent::getResult)
                    .singleOrEmpty()
                    .doOnCancel(run.cancellation()::cancel)
                    .timeout(timeout, Mono.error(new ChatApiException(504, "AGENT_TIMEOUT", "Agent 问答超时，请缩小问题范围后重试")))
                    .block();
        } catch (ChatApiException error) {
            throw error;
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "Agent 执行失败，异常类型 {0}", error.getClass().getSimpleName());
            throw new ChatApiException(502, "AGENT_MODEL_FAILED", "Agent 调用失败，请检查模型地址和工具调用支持情况");
        }
    }

    private static AgentChatResponseVO response(String id, Msg reply, AgentRunContext run) {
        var calls = run.toolCalls();
        var sources = run.sources();
        if (reply != null && reply.getGenerateReason() == GenerateReason.MAX_ITERATIONS) {
            return new AgentChatResponseVO(id, "ITERATION_LIMIT", "本轮已达到检索迭代上限，请缩小问题范围。", calls, sources);
        }
        if (reply == null || reply.hasContentBlocks(ToolUseBlock.class) || reply.getTextContent() == null
                || reply.getTextContent().isBlank() || reply.getGenerateReason() == GenerateReason.INTERRUPTED) {
            throw new ChatApiException(502, "AGENT_RESPONSE_INCOMPLETE", "Agent 没有返回完整回答");
        }
        if (!calls.isEmpty() && sources.isEmpty()) {
            return new AgentChatResponseVO(id, "NO_EVIDENCE", "本轮检索未取得可用资料，无法依据知识库回答。", calls, sources);
        }
        return new AgentChatResponseVO(id, "COMPLETED", reply.getTextContent(), calls, sources);
    }
}
