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
    private Long parseVersion;
    private Long chunkVersion;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer chunkCount;
    private OffsetDateTime chunkedAt;
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
    public Long getParseVersion() { return parseVersion; }
    public void setParseVersion(Long parseVersion) { this.parseVersion = parseVersion; }
    public Long getChunkVersion() { return chunkVersion; }
    public void setChunkVersion(Long chunkVersion) { this.chunkVersion = chunkVersion; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public Integer getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public OffsetDateTime getChunkedAt() { return chunkedAt; }
    public void setChunkedAt(OffsetDateTime chunkedAt) { this.chunkedAt = chunkedAt; }
    private Long chunkRevision;
    public Long getChunkRevision() { return chunkRevision; }
    public void setChunkRevision(Long chunkRevision) { this.chunkRevision = chunkRevision; }
    private String indexStatus;
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
    private String indexError;
    public String getIndexError() { return indexError; }
    public void setIndexError(String indexError) { this.indexError = indexError; }
    private OffsetDateTime indexStartedAt;
    public OffsetDateTime getIndexStartedAt() { return indexStartedAt; }
    public void setIndexStartedAt(OffsetDateTime indexStartedAt) { this.indexStartedAt = indexStartedAt; }
    private String indexToken;
    public String getIndexToken() { return indexToken; }
    public void setIndexToken(String indexToken) { this.indexToken = indexToken; }
    private Long indexedRevision;
    public Long getIndexedRevision() { return indexedRevision; }
    public void setIndexedRevision(Long indexedRevision) { this.indexedRevision = indexedRevision; }
    private String indexModel;
    public String getIndexModel() { return indexModel; }
    public void setIndexModel(String indexModel) { this.indexModel = indexModel; }
    private String indexProfile;
    public String getIndexProfile() { return indexProfile; }
    public void setIndexProfile(String indexProfile) { this.indexProfile = indexProfile; }
    private Integer indexCount;
    public Integer getIndexCount() { return indexCount; }
    public void setIndexCount(Integer indexCount) { this.indexCount = indexCount; }
    private OffsetDateTime indexedAt;
    public OffsetDateTime getIndexedAt() { return indexedAt; }
    public void setIndexedAt(OffsetDateTime indexedAt) { this.indexedAt = indexedAt; }
}
