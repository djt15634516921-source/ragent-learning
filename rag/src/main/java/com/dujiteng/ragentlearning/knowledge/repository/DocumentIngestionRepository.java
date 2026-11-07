package com.dujiteng.ragentlearning.knowledge.repository;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.entity.DocumentIngestionTaskDO;
import com.dujiteng.ragentlearning.knowledge.mapper.DocumentIngestionMapper;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeDocumentMapper;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeLifecycleMapper;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentIngestionRepository {
    private final DocumentIngestionMapper tasks;
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeLifecycleMapper lifecycle;

    public DocumentIngestionRepository(DocumentIngestionMapper tasks, KnowledgeDocumentMapper documents, KnowledgeLifecycleMapper lifecycle) {
        this.tasks = tasks;
        this.documents = documents;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public Submission submit(long userId, long baseId, long documentId, int chunkSize, int overlap,
                             boolean synchronous, long leaseMs) {
        lockDocument(userId, baseId, documentId);
        var existing = tasks.active(documentId);
        if (existing != null) {
            if (existing.chunkSize() != chunkSize || existing.overlap() != overlap) {
                throw new ChatApiException(409, "INGESTION_PARAMETERS_CONFLICT", "已有任务正在使用另一组切分参数");
            }
            return new Submission(existing, false);
        }
        if (tasks.activeCount() >= 100) throw new ChatApiException(503, "INGESTION_BUSY", "待入库任务较多，请稍后重试");
        String id = UUID.randomUUID().toString();
        String token = synchronous ? UUID.randomUUID().toString() : null;
        tasks.create(id, documentId, userId, baseId, synchronous ? "RUNNING" : "PENDING", chunkSize, overlap, token, leaseMs);
        return new Submission(tasks.selectById(id), true);
    }

    @Transactional
    public DocumentIngestionTaskDO retry(long userId, long baseId, String id) {
        var old = requireOwned(userId, baseId, id);
        lockDocument(userId, baseId, old.documentId());
        old = requireOwned(userId, baseId, id);
        if (!"FAILED".equals(old.status()) || tasks.active(old.documentId()) != null) {
            throw new ChatApiException(409, "INGESTION_NOT_RETRYABLE", "只有失败且没有其他执行任务的文档可以重试");
        }
        tasks.retry(id);
        return tasks.selectById(id);
    }

    public DocumentIngestionTaskDO requireOwned(long userId, long baseId, String id) {
        var task = tasks.owned(userId, baseId, id);
        if (task == null) throw new ChatApiException(404, "INGESTION_NOT_FOUND", "入库任务不存在或不属于当前用户");
        return task;
    }

    @Transactional
    public void recoverExpired() {
        tasks.fenceExpiredParsing();
        tasks.fenceExpiredIndexing();
        tasks.failExpired();
    }

    private void lockDocument(long userId, long baseId, long documentId) {
        // 与删除统一先锁库、再锁文档，避免任务外键检查与删除形成相反的锁顺序。
        if (lifecycle.lockBase(userId, baseId) == null) {
            throw new ChatApiException(404, "KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或不属于当前用户");
        }
        if (documents.lockOwned(userId, baseId, documentId) == null) {
            throw new ChatApiException(404, "DOCUMENT_NOT_FOUND", "文档不存在或不属于当前用户");
        }
    }

    public record Submission(DocumentIngestionTaskDO task, boolean created) { }
}
