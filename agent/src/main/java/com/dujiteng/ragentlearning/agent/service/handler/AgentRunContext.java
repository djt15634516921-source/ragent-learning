package com.dujiteng.ragentlearning.agent.service.handler;

import com.dujiteng.ragentlearning.agent.vo.AgentChatResponseVO.Source;
import com.dujiteng.ragentlearning.agent.vo.AgentChatResponseVO.ToolCall;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 每轮从可信入口绑定作用域、取消信号和证据，随 RuntimeContext 传给共享工具。 */
public class AgentRunContext {
    private final long userId;
    private final long knowledgeBaseId;
    private final int maxCalls;
    private final StreamCancellation cancellation = new StreamCancellation();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Set<String> observedCalls = ConcurrentHashMap.newKeySet();
    private final List<ToolCall> calls = new ArrayList<>();
    private final Map<ChunkVersion, Source> sources = new LinkedHashMap<>();

    public AgentRunContext(long userId, long knowledgeBaseId, int maxCalls) {
        this.userId = userId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.maxCalls = maxCalls;
    }

    public long userId() {
        return userId;
    }

    public long knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public StreamCancellation cancellation() {
        return cancellation;
    }

    public int maxCalls() {
        return maxCalls;
    }

    public int startToolCall(String id) {
        if (id != null) {
            observedCalls.add(id);
        }
        return sequence.incrementAndGet();
    }

    public synchronized List<Source> numberSources(List<RetrievedSourceVO> hits) {
        List<Source> evidence = new ArrayList<>();
        for (var hit : hits) {
            var key = new ChunkVersion(hit.chunkId(), hit.sourceVersion());
            evidence.add(sources.computeIfAbsent(key, ignored -> new Source(sources.size() + 1, hit)));
        }
        return evidence;
    }

    public synchronized void record(ToolCall call) {
        calls.add(call);
    }

    /** SDK 在进入 Java 工具前拒绝参数时，从工具结果事件补记。 */
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

    private record ChunkVersion(long chunkId, long sourceVersion) {
    }
}
