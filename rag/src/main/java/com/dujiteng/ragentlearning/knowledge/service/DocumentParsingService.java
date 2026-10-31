package com.dujiteng.ragentlearning.knowledge.service;

import com.dujiteng.ragentlearning.knowledge.vo.DocumentTextVO;

public interface DocumentParsingService {
    DocumentTextVO parse(long userId, long knowledgeBaseId, long documentId);
    DocumentTextVO text(long userId, long knowledgeBaseId, long documentId);
}
