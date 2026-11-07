package com.dujiteng.ragentlearning.knowledge.service.impl;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.processing.DocumentProcessingCoordinator;
import com.dujiteng.ragentlearning.infra.embedding.EmbeddingClient;
import com.dujiteng.ragentlearning.knowledge.repository.DocumentVectorRepository;
import com.dujiteng.ragentlearning.knowledge.service.DocumentIndexService;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentIndexVO;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class DocumentIndexServiceImpl implements DocumentIndexService {
    private static final System.Logger LOG = System.getLogger(DocumentIndexServiceImpl.class.getName());
    private final DocumentProcessingCoordinator coordinator;
    private final DocumentVectorRepository repository;
    private final EmbeddingClient embedding;
    private final int maximumChunks;

    public DocumentIndexServiceImpl(DocumentVectorRepository repository, EmbeddingClient embedding, DocumentProcessingCoordinator coordinator,
            @Value("${rag.max-index-chunks:500}") int maximumChunks) {
        if (maximumChunks < 1 || maximumChunks > 5000) throw new IllegalArgumentException("向量化上限须为 1～5000");
        this.coordinator = coordinator;
        this.repository = repository;
        this.embedding = embedding;
        this.maximumChunks = maximumChunks;
    }

    @Override
    public DocumentIndexVO status(long userId, long knowledgeBaseId, long documentId) {
        try { return repository.status(userId, knowledgeBaseId, documentId, embedding.profile());
        } catch (DataAccessException | TransactionException error) { throw database(error); }
    }

    @Override
    public DocumentIndexVO index(long userId, long knowledgeBaseId, long documentId) {
        return coordinator.manual(userId, knowledgeBaseId, documentId, () -> indexDocument(userId, knowledgeBaseId, documentId));
    }

    private DocumentIndexVO indexDocument(long userId, long knowledgeBaseId, long documentId) {
        DocumentVectorRepository.Task task;
        try { task = repository.begin(userId, knowledgeBaseId, documentId, embedding.profile(), maximumChunks);
        } catch (DataAccessException | TransactionException error) { throw database(error); }
        if (task == null) return status(userId, knowledgeBaseId, documentId);
        try {
            List<String> values = new ArrayList<>();
            for (int start = 0; start < task.chunks().size(); start += EmbeddingClient.BATCH_SIZE) {
                repository.renew(task);
                var batch = task.chunks().subList(start, Math.min(start + EmbeddingClient.BATCH_SIZE, task.chunks().size()));
                values.addAll(embedding.embed(batch.stream().map(c -> c.getContent()).toList()));
            }
            repository.finish(task, embedding.model(), embedding.profile(), values);
            return status(userId, knowledgeBaseId, documentId);
        } catch (RuntimeException error) {
            ChatApiException failure = error instanceof ChatApiException api ? api
                    : error instanceof DataAccessException || error instanceof TransactionException ? database(error)
                    : new ChatApiException(500, "DOCUMENT_INDEX_FAILED", "文档向量化失败，请查看后端运行情况");
            try { repository.fail(task, failure.code() + ": " + failure.getMessage());
            } catch (DataAccessException | TransactionException stateError) {
                LOG.log(System.Logger.Level.ERROR, "记录向量化失败状态失败，文档 {0}，异常类型 {1}", documentId, stateError.getClass().getName());
            }
            throw failure;
        }
    }

    private static ChatApiException database(RuntimeException error) {
        LOG.log(System.Logger.Level.ERROR, "向量数据库操作失败，异常类型 {0}", error.getClass().getName());
        return new ChatApiException(503, "DOCUMENT_INDEX_DATABASE_FAILED", "向量数据保存或读取失败，请检查数据库", error);
    }
}
