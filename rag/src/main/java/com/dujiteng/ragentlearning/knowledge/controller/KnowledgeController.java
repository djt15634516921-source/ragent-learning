package com.dujiteng.ragentlearning.knowledge.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dujiteng.ragentlearning.knowledge.dto.CreateKnowledgeBaseRequest;
import com.dujiteng.ragentlearning.knowledge.service.DocumentParsingService;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeService;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentTextVO;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentVO;
import com.dujiteng.ragentlearning.knowledge.vo.KnowledgeBaseVO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeController {
    private final KnowledgeService service;
    private final DocumentParsingService parsing;

    public KnowledgeController(KnowledgeService service, DocumentParsingService parsing) {
        this.service = service;
        this.parsing = parsing;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBaseVO create(@RequestBody CreateKnowledgeBaseRequest request) {
        return service.create(StpUtil.getLoginIdAsLong(), request == null ? null : request.name(),
                request == null ? null : request.description());
    }

    @GetMapping
    public List<KnowledgeBaseVO> list() {
        return service.list(StpUtil.getLoginIdAsLong());
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public List<DocumentVO> documents(@PathVariable long knowledgeBaseId) {
        return service.documents(StpUtil.getLoginIdAsLong(), knowledgeBaseId);
    }

    @PostMapping(value = "/{knowledgeBaseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentVO upload(@PathVariable long knowledgeBaseId,
                             @RequestPart(value = "file", required = false) MultipartFile file) {
        return service.upload(StpUtil.getLoginIdAsLong(), knowledgeBaseId, file);
    }

    @GetMapping("/{knowledgeBaseId}/documents/{documentId}/file")
    public ResponseEntity<Resource> download(@PathVariable long knowledgeBaseId, @PathVariable long documentId) {
        var download = service.download(StpUtil.getLoginIdAsLong(), knowledgeBaseId, documentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(download.document().size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.document().filename(), StandardCharsets.UTF_8).build().toString())
                .body(download.resource());
    }

    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/parse")
    public DocumentTextVO parse(@PathVariable long knowledgeBaseId, @PathVariable long documentId) {
        return parsing.parse(StpUtil.getLoginIdAsLong(), knowledgeBaseId, documentId);
    }

    @GetMapping("/{knowledgeBaseId}/documents/{documentId}/text")
    public DocumentTextVO text(@PathVariable long knowledgeBaseId, @PathVariable long documentId) {
        return parsing.text(StpUtil.getLoginIdAsLong(), knowledgeBaseId, documentId);
    }

}
