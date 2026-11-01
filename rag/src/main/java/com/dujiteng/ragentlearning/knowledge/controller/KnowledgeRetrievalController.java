package com.dujiteng.ragentlearning.knowledge.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dujiteng.ragentlearning.knowledge.dto.KnowledgeSearchRequest;
import com.dujiteng.ragentlearning.knowledge.service.DocumentIndexService;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeRetrievalService;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentIndexVO;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}")
public class KnowledgeRetrievalController {
    private final DocumentIndexService indexing;
    private final KnowledgeRetrievalService retrieval;
    public KnowledgeRetrievalController(DocumentIndexService indexing, KnowledgeRetrievalService retrieval) {
        this.indexing = indexing;
        this.retrieval = retrieval;
    }
    @PostMapping("/documents/{documentId}/index")
    public DocumentIndexVO index(@PathVariable long knowledgeBaseId, @PathVariable long documentId) {
        return indexing.index(StpUtil.getLoginIdAsLong(), knowledgeBaseId, documentId);
    }
    @GetMapping("/documents/{documentId}/index")
    public DocumentIndexVO status(@PathVariable long knowledgeBaseId, @PathVariable long documentId) {
        return indexing.status(StpUtil.getLoginIdAsLong(), knowledgeBaseId, documentId);
    }
    @PostMapping("/search")
    public List<RetrievedSourceVO> search(@PathVariable long knowledgeBaseId, @RequestBody KnowledgeSearchRequest request) {
        return retrieval.search(StpUtil.getLoginIdAsLong(), knowledgeBaseId, request == null ? null : request.question());
    }
}
