package com.dujiteng.ragentlearning.knowledge.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dujiteng.ragentlearning.knowledge.dto.ChunkRequest;
import com.dujiteng.ragentlearning.knowledge.service.DocumentIngestionService;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentIngestionVO;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}")
public class DocumentIngestionController {
    private final DocumentIngestionService ingestion;

    public DocumentIngestionController(DocumentIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping(value = "/documents/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentIngestionVO> upload(@PathVariable long knowledgeBaseId,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(required = false) Integer chunkSize, @RequestParam(required = false) Integer overlap,
            @RequestParam(defaultValue = "true") boolean async) {
        return response(ingestion.upload(StpUtil.getLoginIdAsLong(), knowledgeBaseId, file, new ChunkRequest(chunkSize, overlap), async));
    }

    @PostMapping("/documents/{documentId}/ingest")
    public ResponseEntity<DocumentIngestionVO> ingest(@PathVariable long knowledgeBaseId, @PathVariable long documentId,
            @RequestBody(required = false) ChunkRequest request, @RequestParam(defaultValue = "true") boolean async) {
        return response(ingestion.submit(StpUtil.getLoginIdAsLong(), knowledgeBaseId, documentId, request, async));
    }

    @GetMapping("/ingestion-tasks")
    public List<DocumentIngestionVO> tasks(@PathVariable long knowledgeBaseId) {
        return ingestion.list(StpUtil.getLoginIdAsLong(), knowledgeBaseId);
    }

    @GetMapping("/ingestion-tasks/{taskId}")
    public DocumentIngestionVO status(@PathVariable long knowledgeBaseId, @PathVariable String taskId) {
        return ingestion.status(StpUtil.getLoginIdAsLong(), knowledgeBaseId, taskId);
    }

    @PostMapping("/ingestion-tasks/{taskId}/retry")
    public ResponseEntity<DocumentIngestionVO> retry(@PathVariable long knowledgeBaseId, @PathVariable String taskId) {
        return response(ingestion.retry(StpUtil.getLoginIdAsLong(), knowledgeBaseId, taskId));
    }

    private static ResponseEntity<DocumentIngestionVO> response(DocumentIngestionVO result) {
        return ResponseEntity.status("PENDING".equals(result.status()) || "RUNNING".equals(result.status())
                ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result);
    }
}
