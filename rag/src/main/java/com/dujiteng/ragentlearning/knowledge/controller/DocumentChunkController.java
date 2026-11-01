package com.dujiteng.ragentlearning.knowledge.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dujiteng.ragentlearning.knowledge.dto.ChunkRequest;
import com.dujiteng.ragentlearning.knowledge.service.DocumentChunkService;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentChunksVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/chunks")
public class DocumentChunkController {
    private final DocumentChunkService service;

    public DocumentChunkController(DocumentChunkService service) { this.service = service; }

    @PostMapping
    public DocumentChunksVO chunk(@PathVariable long knowledgeBaseId, @PathVariable long documentId,
                                   @RequestBody(required = false) ChunkRequest request) {
        return service.chunk(StpUtil.getLoginIdAsLong(), knowledgeBaseId, documentId, request);
    }

    @GetMapping
    public DocumentChunksVO list(@PathVariable long knowledgeBaseId, @PathVariable long documentId,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return service.list(StpUtil.getLoginIdAsLong(), knowledgeBaseId, documentId, page, size);
    }
}
