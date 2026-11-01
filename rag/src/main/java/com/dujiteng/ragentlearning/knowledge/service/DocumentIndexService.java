package com.dujiteng.ragentlearning.knowledge.service;

import com.dujiteng.ragentlearning.knowledge.vo.DocumentIndexVO;

public interface DocumentIndexService {
    DocumentIndexVO index(long userId, long knowledgeBaseId, long documentId);
    DocumentIndexVO status(long userId, long knowledgeBaseId, long documentId);
}
