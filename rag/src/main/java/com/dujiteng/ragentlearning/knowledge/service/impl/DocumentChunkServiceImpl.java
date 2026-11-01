package com.dujiteng.ragentlearning.knowledge.service.impl;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.chunk.TextSplitter;
import com.dujiteng.ragentlearning.knowledge.dto.ChunkRequest;
import com.dujiteng.ragentlearning.knowledge.repository.DocumentChunkRepository;
import com.dujiteng.ragentlearning.knowledge.service.DocumentChunkService;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentChunksVO;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class DocumentChunkServiceImpl implements DocumentChunkService {
    private final DocumentChunkRepository repository;
    private final TextSplitter splitter;

    public DocumentChunkServiceImpl(DocumentChunkRepository repository, TextSplitter splitter) {
        this.repository = repository;
        this.splitter = splitter;
    }

    @Override
    public DocumentChunksVO chunk(long userId, long knowledgeBaseId, long documentId, ChunkRequest request) {
        try {
            // 先检查归属；切分计算在写入事务之外，写入前再次校验原文版本。
            var source = repository.source(userId, knowledgeBaseId, documentId);
            int chunkSize = request == null || request.chunkSize() == null ? 800 : request.chunkSize();
            int overlap = request == null || request.overlap() == null ? 120 : request.overlap();
            TextSplitter.validate(chunkSize, overlap);
            var segments = splitter.split(source.getParsedText(), chunkSize, overlap);
            if (segments.isEmpty()) throw new ChatApiException(422, "DOCUMENT_TEXT_EMPTY", "没有可切分的正文，请重新解析");
            repository.replace(userId, knowledgeBaseId, documentId, source.getParseVersion(), chunkSize, overlap, segments);
            return repository.list(userId, knowledgeBaseId, documentId, 1, 20);
        } catch (DataAccessException | TransactionException error) {
            throw databaseFailure(error);
        }
    }

    @Override
    public DocumentChunksVO list(long userId, long knowledgeBaseId, long documentId, int page, int size) {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 100) {
            throw new ChatApiException(400, "CHUNK_PAGE_INVALID", "页码须为正整数，每页须为 1～100 条");
        }
        try { return repository.list(userId, knowledgeBaseId, documentId, page, size); }
        catch (DataAccessException | TransactionException error) { throw databaseFailure(error); }
    }

    private static ChatApiException databaseFailure(RuntimeException error) {
        System.getLogger(DocumentChunkServiceImpl.class.getName()).log(System.Logger.Level.ERROR,
                "文档片段数据库操作失败，异常类型：{0}", error.getClass().getName());
        return new ChatApiException(503, "DOCUMENT_CHUNK_DATABASE_FAILED", "无法读写文档片段；原有有效片段会保留，请检查数据库后重试", error);
    }
}
