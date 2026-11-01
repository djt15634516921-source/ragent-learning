package com.dujiteng.ragentlearning.knowledge.service;

import com.dujiteng.ragentlearning.knowledge.dto.ChunkRequest;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentChunksVO;

public interface DocumentChunkService {
    DocumentChunksVO chunk(long userId, long knowledgeBaseId, long documentId, ChunkRequest request);
    DocumentChunksVO list(long userId, long knowledgeBaseId, long documentId, int page, int size);
}
