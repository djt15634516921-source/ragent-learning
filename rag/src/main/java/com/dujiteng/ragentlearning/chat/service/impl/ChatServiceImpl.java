package com.dujiteng.ragentlearning.chat.service.impl;

import com.dujiteng.ragentlearning.chat.dto.ChatRequest;
import com.dujiteng.ragentlearning.chat.memory.ConversationMemoryStore.Conversation;
import com.dujiteng.ragentlearning.chat.memory.ConversationMemoryStore;
import com.dujiteng.ragentlearning.chat.service.ChatService;
import com.dujiteng.ragentlearning.chat.vo.ChatMessageVO;
import com.dujiteng.ragentlearning.chat.vo.ChatResponseVO;
import com.dujiteng.ragentlearning.chat.vo.ConversationVO;
import com.dujiteng.ragentlearning.chat.vo.QueryRewriteVO;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.LLMService;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import com.dujiteng.ragentlearning.rag.core.prompt.RAGPromptService;
import com.dujiteng.ragentlearning.rag.core.workflow.RagContext;
import com.dujiteng.ragentlearning.rag.core.workflow.RagWorkflow;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievalTraceVO;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class ChatServiceImpl implements ChatService {
    private static final int MAX_QUESTION_CHARS = 8_000;
    private static final int MAX_CONVERSATION_ID_CHARS = 128;
    // 保留用户原有基础提示词；知识库规则由提示词服务追加。
    private static final String SYSTEM_PROMPT =
            "你是一名Java后端学习助手，请用中文回答。"
            + "回答第一行固定为【Ragent学习助手】。"
            + "随后给出简短解释和一个具体例子。";
    private final LLMService llm;
    private final ConversationMemoryStore memoryStore;
    private final RagWorkflow workflow;
    private final RAGPromptService prompts;

    public ChatServiceImpl(LLMService llm, ConversationMemoryStore memoryStore, RagWorkflow workflow, RAGPromptService prompts) {
        this.llm = llm;
        this.memoryStore = memoryStore;
        this.workflow = workflow;
        this.prompts = prompts;
    }

    @Override
    public ChatResponseVO chat(long userId, String conversationId, String question, Long knowledgeBaseId, Boolean rerank) {
        ChatRequest input = validateRequest(conversationId, question);
        Conversation conversation = prepare(userId, input.conversationId(), knowledgeBaseId);
        synchronized (conversation) {
            var cancellation = new StreamCancellation();
            var history = conversation.history();
            var context = knowledgeBaseId == null ? null
                    : workflow.prepare(userId, knowledgeBaseId, input.question(), history, cancellation, rerank);
            String answer = context != null && context.directAnswer() != null ? context.directAnswer()
                    : llm.chat(prompts.build(SYSTEM_PROMPT, input.question(), history, context), cancellation);
            var response = response(answer, knowledgeBaseId, context);
            cancellation.commit(() -> conversation.appendTurn(input.question(), answer, response.sources()));
            return response;
        }
    }

    @Override
    public ChatResponseVO streamChat(long userId, String conversationId, String question, Long knowledgeBaseId, Boolean rerank,
                                     Consumer<String> onDelta, StreamCancellation cancellation) {
        ChatRequest input = validateRequest(conversationId, question);
        cancellation.check();
        Conversation conversation = prepare(userId, input.conversationId(), knowledgeBaseId);
        synchronized (conversation) {
            cancellation.check();
            var history = conversation.history();
            var context = knowledgeBaseId == null ? null
                    : workflow.prepare(userId, knowledgeBaseId, input.question(), history, cancellation, rerank);
            cancellation.check();
            String answer;
            if (context != null && context.directAnswer() != null) {
                answer = context.directAnswer();
                onDelta.accept(answer);
            } else {
                answer = llm.streamChat(prompts.build(SYSTEM_PROMPT, input.question(), history, context), onDelta, cancellation);
            }
            cancellation.check();
            var response = response(answer, knowledgeBaseId, context);
            cancellation.commit(() -> conversation.appendTurn(input.question(), answer, response.sources()));
            return response;
        }
    }

    private static ChatResponseVO response(String answer, Long knowledgeBaseId, RagContext context) {
        return new ChatResponseVO(answer, context == null ? List.of() : context.sources(), knowledgeBaseId,
                context == null ? null : QueryRewriteVO.from(context.rewrite()),
                context == null ? null : RetrievalTraceVO.from(context.retrieval()));
    }

    /** 在 SSE 建立之前确定归属；worker 随后仍会检查。新会话此时可能只有会话行、还没有消息。 */
    @Override
    public void prepareConversation(long userId, String conversationId, Long knowledgeBaseId) {
        prepare(userId, conversationId, knowledgeBaseId);
    }

    @Override
    public List<ChatMessageVO> history(long userId, String conversationId) {
        return memoryStore.history(userId, validateConversationId(conversationId));
    }

    @Override
    public List<ConversationVO> listConversations(long userId) {
        return memoryStore.listConversations(userId);
    }

    @Override
    public ChatRequest validateRequest(String conversationId, String question) {
        String normalizedId = validateConversationId(conversationId);
        if (question == null || question.isBlank()) {
            throw new ChatApiException(400, "QUESTION_REQUIRED", "question 不能为空");
        }
        String normalized = question.strip();
        if (normalized.length() > MAX_QUESTION_CHARS) {
            throw new ChatApiException(400, "QUESTION_TOO_LONG", "本课接口的问题长度上限为 8000 个字符");
        }
        return new ChatRequest(normalizedId, normalized);
    }

    private Conversation prepare(long userId, String cid, Long knowledgeBaseId) {
        if (knowledgeBaseId != null) workflow.requireOwner(userId, knowledgeBaseId);
        return memoryStore.getOrCreate(userId, cid, knowledgeBaseId);
    }

    private String validateConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ChatApiException(400, "CONVERSATION_ID_REQUIRED", "conversationId 不能为空");
        }
        String normalized = conversationId.strip();
        if (normalized.length() > MAX_CONVERSATION_ID_CHARS) {
            throw new ChatApiException(400, "CONVERSATION_ID_TOO_LONG", "conversationId 最多 128 个字符");
        }
        return normalized;
    }
}
