package com.dujiteng.ragentlearning.agent.tool;

import com.dujiteng.ragentlearning.agent.service.handler.AgentRunContext;
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
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/** 共享的只读工具，从本次调用的 RuntimeContext 取得可信作用域。 */
public class KnowledgeSearchTool implements AgentTool {
    private static final String NAME = "search_knowledge";
    private static final int MAX_QUERY_LENGTH = 2000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final System.Logger LOG = System.getLogger(KnowledgeSearchTool.class.getName());
    private final KnowledgeRetrievalService retrieval;

    public KnowledgeSearchTool(KnowledgeRetrievalService retrieval) {
        this.retrieval = retrieval;
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
        var runtime = param.getRuntimeContext();
        var run = runtime == null ? null : runtime.get(AgentRunContext.class);
        if (run == null) {
            return result(id, "知识库检索缺少本轮会话范围。", ToolResultState.ERROR);
        }
        try {
            run.cancellation().check();
            int number = run.startToolCall(id);
            // 一个模型回复可能含多个 tool_call，单独限制实际检索次数。
            if (number > run.maxCalls()) {
                if (number == run.maxCalls() + 1) {
                    run.record(new ToolCall(number, NAME, null, "LIMIT_REACHED", "AGENT_TOOL_LIMIT", List.of()));
                }
                return result(id, "已达到本轮检索次数上限，请用已返回的资料作答。", ToolResultState.ERROR);
            }
            Map<String, Object> input = param.getInput();
            if (input == null || input.size() != 1 || !(input.get("query") instanceof String query)
                    || query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
                run.record(new ToolCall(number, NAME, null, "INVALID_ARGUMENTS", "AGENT_TOOL_QUERY_INVALID", List.of()));
                return result(id, "请只传入不超过 2000 字符的非空 query。", ToolResultState.ERROR);
            }
            return search(run, id, number, query.strip());
        } catch (StreamCancellation.Cancelled stopped) {
            return result(id, "本轮检索已结束。", ToolResultState.INTERRUPTED);
        }
    }

    private ToolResultBlock search(AgentRunContext run, String id, int number, String query) {
        List<RetrievedSourceVO> hits;
        try {
            hits = retrieval.retrieve(run.userId(), run.knowledgeBaseId(), query, RetrievalOptions.defaults(),
                    run.cancellation()).sources();
        } catch (ChatApiException error) {
            run.cancellation().check();
            LOG.log(System.Logger.Level.WARNING, "Agent 检索失败，错误码 {0}", error.code());
            run.record(new ToolCall(number, NAME, query, "ERROR", error.code(), List.of()));
            return result(id, "知识库检索失败。请根据已有资料回答，或说明资料不可用。", ToolResultState.ERROR);
        }
        run.cancellation().check();
        List<Source> evidence = run.numberSources(hits);
        run.record(new ToolCall(number, NAME, query, evidence.isEmpty() ? "NO_EVIDENCE" : "SUCCESS", null,
                evidence.stream().map(Source::citationId).toList()));
        var documents = evidence.stream().map(source -> Map.of(
                "citationId", source.citationId(),
                "filename", source.fragment().filename(),
                "content", source.fragment().content())).toList();
        return result(id, JSON.writeValueAsString(Map.of("sources", documents)), ToolResultState.SUCCESS);
    }

    private static ToolResultBlock result(String id, String text, ToolResultState state) {
        return ToolResultBlock.builder().id(id).name(NAME).state(state)
                .output(TextBlock.builder().text(text).build()).build();
    }
}
