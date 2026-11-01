package com.dujiteng.ragentlearning.knowledge.service;

import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;

public interface KnowledgeRetrievalService {
    void requireOwner(long userId, long knowledgeBaseId);
    List<RetrievedSourceVO> search(long userId, long knowledgeBaseId, String question);
}
