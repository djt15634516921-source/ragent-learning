package com.dujiteng.ragentlearning.knowledge.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeDeletionService;
import com.dujiteng.ragentlearning.knowledge.vo.DeletionResultVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeDeletionController {
    private final KnowledgeDeletionService service;

    public KnowledgeDeletionController(KnowledgeDeletionService service) { this.service = service; }

    @DeleteMapping("/{knowledgeBaseId}")
    public DeletionResultVO deleteBase(@PathVariable long knowledgeBaseId) {
        return service.deleteBase(StpUtil.getLoginIdAsLong(), knowledgeBaseId);
    }

    @DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
    public DeletionResultVO deleteDocument(@PathVariable long knowledgeBaseId, @PathVariable long documentId) {
        return service.deleteDocument(StpUtil.getLoginIdAsLong(), knowledgeBaseId, documentId);
    }
}
