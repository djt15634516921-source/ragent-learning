package com.dujiteng.ragentlearning.agent.tool;

import com.dujiteng.ragentlearning.agent.vo.AgentChatResponseVO.Source;
import com.dujiteng.ragentlearning.agent.vo.AgentChatResponseVO.ToolCall;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeRetrievalService;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalOptions;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/** 请求级只读工具。模型参数只有 query，归属来自经过验证的 HTTP 请求。 */
public class KnowledgeSearchTool implements AgentTool {
    private static final String NAME = "search_knowledge";
    private static final int MAX_QUERY_LENGTH = 2000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final System.Logger LOG = System.getLogger(KnowledgeSearchTool.class.getName());
    private final KnowledgeRetrievalService retrieval;
    private final long userId;
    private final long knowledgeBaseId;
    private final StreamCancellation cancellation;
    private final int maxCalls;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Set<String> observedCalls = ConcurrentHashMap.newKeySet();
    private final List<ToolCall> calls = new ArrayList<>();
    private final Map<ChunkVersion, Source> sources = new LinkedHashMap<>();

    public KnowledgeSearchTool(KnowledgeRetrievalService retrieval, long userId, long knowledgeBaseId,
                               StreamCancellation cancellation, int maxCalls) {
        this.retrieval = retrieval;
        this.userId = userId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.cancellation = cancellation;
        this.maxCalls = maxCalls;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "查找当前选中知识库的相关片段，返回正文和用于回答的引用编号";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of("query", Map.of("type", "string", "description", "完整独立的检索问题")),
                "required", List.of("query"), "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param)).subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(ToolCallParam param) {
        String id = param.getToolUseBlock().getId();
        if (id != null) {
            observedCalls.add(id);
        }
        int number = sequence.incrementAndGet();
        try {
            cancellation.check();
            // 一个模型回复可能含多个 tool_call，单独限制实际检索次数。
            if (number > maxCalls) {
                if (number == maxCalls + 1) {
                    record(number, null, "LIMIT_REACHED", "AGENT_TOOL_LIMIT", List.of());
                }
                return result(id, "已达到本轮检索次数上限，请用已返回的资料作答。", ToolResultState.ERROR);
            }
            Map<String, Object> input = param.getInput();
            if (input == null || input.size() != 1 || !(input.get("query") instanceof String query)
                    || query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
                record(number, null, "INVALID_ARGUMENTS", "AGENT_TOOL_QUERY_INVALID", List.of());
                return result(id, "请只传入不超过 2000 字符的非空 query。", ToolResultState.ERROR);
            }
            return search(id, number, query.strip());
        } catch (StreamCancellation.Cancelled stopped) {
            return result(id, "本轮检索已结束。", ToolResultState.INTERRUPTED);
        }
    }

    private ToolResultBlock search(String id, int number, String query) {
        List<RetrievedSourceVO> hits;
        try {
            hits = retrieval.retrieve(userId, knowledgeBaseId, query, RetrievalOptions.defaults(), cancellation).sources();
        } catch (ChatApiException error) {
            cancellation.check();
            LOG.log(System.Logger.Level.WARNING, "Agent 检索失败，错误码 {0}", error.code());
            record(number, query, "ERROR", error.code(), List.of());
            return result(id, "知识库检索失败。请根据已有资料回答，或说明资料不可用。", ToolResultState.ERROR);
        }
        cancellation.check();
        List<Source> evidence = numberSources(hits);
        record(number, query, evidence.isEmpty() ? "NO_EVIDENCE" : "SUCCESS", null,
                evidence.stream().map(Source::citationId).toList());
        var documents = evidence.stream().map(source -> Map.of(
                "citationId", source.citationId(),
                "filename", source.fragment().filename(),
                "content", source.fragment().content())).toList();
        return result(id, JSON.writeValueAsString(Map.of("sources", documents)), ToolResultState.SUCCESS);
    }

    private synchronized List<Source> numberSources(List<RetrievedSourceVO> hits) {
        List<Source> evidence = new ArrayList<>();
        for (var hit : hits) {
            var key = new ChunkVersion(hit.chunkId(), hit.sourceVersion());
            evidence.add(sources.computeIfAbsent(key, ignored -> new Source(sources.size() + 1, hit)));
        }
        return evidence;
    }

    private synchronized void record(int number, String query, String status, String code, List<Integer> citationIds) {
        calls.add(new ToolCall(number, NAME, query, status, code, citationIds));
    }

    /** SDK 会在执行 Java 工具前拒绝非法参数，这类失败从执行事件补记。 */
    public synchronized void recordFrameworkRejection(String id, String name) {
        if (id != null && !observedCalls.add(id)) {
            return;
        }
        int number = sequence.incrementAndGet();
        if (number <= maxCalls + 1) {
            calls.add(new ToolCall(number, name, null, "REJECTED", "AGENT_TOOL_REJECTED", List.of()));
        }
    }

    public synchronized List<ToolCall> toolCalls() {
        return calls.stream().sorted(Comparator.comparingInt(ToolCall::sequence)).toList();
    }

    public synchronized List<Source> sources() {
        return List.copyOf(sources.values());
    }

    private static ToolResultBlock result(String id, String text, ToolResultState state) {
        return ToolResultBlock.builder().id(id).name(NAME).state(state)
                .output(TextBlock.builder().text(text).build()).build();
    }

    private record ChunkVersion(long chunkId, long sourceVersion) {
    }
}
