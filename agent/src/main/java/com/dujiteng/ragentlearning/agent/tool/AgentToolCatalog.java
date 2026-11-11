package com.dujiteng.ragentlearning.agent.tool;

import com.dujiteng.ragentlearning.knowledge.service.KnowledgeRetrievalService;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

@Component
public class AgentToolCatalog {
    private final KnowledgeRetrievalService retrieval;

    public AgentToolCatalog(KnowledgeRetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    public Toolkit buildToolkit() {
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(new KnowledgeSearchTool(retrieval));
        return toolkit;
    }
}
