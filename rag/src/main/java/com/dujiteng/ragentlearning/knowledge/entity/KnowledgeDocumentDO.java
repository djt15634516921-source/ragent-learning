package com.dujiteng.ragentlearning.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("knowledge_document")
public class KnowledgeDocumentDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long knowledgeBaseId;
    private String originalFilename;
    private String fileType;
    private Long fileSize;
    private String storageKey;
    private String status;
    private OffsetDateTime createdAt;
    private String parsedText;
    private String errorMessage;
    private OffsetDateTime parsedAt;
    private OffsetDateTime parseStartedAt;
    private String parseToken;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public String getParsedText() { return parsedText; }
    public void setParsedText(String parsedText) { this.parsedText = parsedText; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getParsedAt() { return parsedAt; }
    public void setParsedAt(OffsetDateTime parsedAt) { this.parsedAt = parsedAt; }
    public OffsetDateTime getParseStartedAt() { return parseStartedAt; }
    public void setParseStartedAt(OffsetDateTime parseStartedAt) { this.parseStartedAt = parseStartedAt; }
    public String getParseToken() { return parseToken; }
    public void setParseToken(String parseToken) { this.parseToken = parseToken; }
}
