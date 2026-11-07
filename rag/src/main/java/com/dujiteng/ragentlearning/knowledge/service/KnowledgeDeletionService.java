package com.dujiteng.ragentlearning.knowledge.service;

import com.dujiteng.ragentlearning.knowledge.vo.DeletionResultVO;

public interface KnowledgeDeletionService {
    DeletionResultVO deleteDocument(long userId, long knowledgeBaseId, long documentId);
    DeletionResultVO deleteBase(long userId, long knowledgeBaseId);
}
