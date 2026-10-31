package com.dujiteng.ragentlearning.knowledge.service;

import com.dujiteng.ragentlearning.knowledge.vo.DocumentDownload;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentVO;
import com.dujiteng.ragentlearning.knowledge.vo.KnowledgeBaseVO;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeService {
    KnowledgeBaseVO create(long userId, String name, String description);
    List<KnowledgeBaseVO> list(long userId);
    List<DocumentVO> documents(long userId, long knowledgeBaseId);
    DocumentVO upload(long userId, long knowledgeBaseId, MultipartFile file);
    DocumentDownload download(long userId, long knowledgeBaseId, long documentId);
}
