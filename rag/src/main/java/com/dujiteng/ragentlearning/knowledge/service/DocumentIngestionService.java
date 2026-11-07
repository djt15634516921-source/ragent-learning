package com.dujiteng.ragentlearning.knowledge.service;

import com.dujiteng.ragentlearning.knowledge.dto.ChunkRequest;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentIngestionVO;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentIngestionService {
    DocumentIngestionVO upload(long userId, long knowledgeBaseId, MultipartFile file, ChunkRequest request, boolean asynchronous);
    DocumentIngestionVO submit(long userId, long knowledgeBaseId, long documentId, ChunkRequest request, boolean asynchronous);
    DocumentIngestionVO status(long userId, long knowledgeBaseId, String taskId);
    List<DocumentIngestionVO> list(long userId, long knowledgeBaseId);
    DocumentIngestionVO retry(long userId, long knowledgeBaseId, String taskId);
}
