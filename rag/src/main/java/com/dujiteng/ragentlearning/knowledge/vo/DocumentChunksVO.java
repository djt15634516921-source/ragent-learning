package com.dujiteng.ragentlearning.knowledge.vo;

import java.time.Instant;
import java.util.List;

public record DocumentChunksVO(long documentId, String filename, long sourceVersion, String documentStatus,
                               String chunkStatus, Integer chunkSize, Integer overlap, Instant chunkedAt,
                               long total, int page, int size, List<DocumentChunkVO> chunks) { }
