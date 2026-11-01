package com.dujiteng.ragentlearning.knowledge.vo;

import java.time.Instant;

public record DocumentVO(long id, long knowledgeBaseId, String filename, String fileType, long size, String status, Instant createdAt, String errorMessage, Instant parsedAt, String chunkStatus, int chunkCount, Instant chunkedAt, String indexStatus, int indexCount, String indexError) { }
