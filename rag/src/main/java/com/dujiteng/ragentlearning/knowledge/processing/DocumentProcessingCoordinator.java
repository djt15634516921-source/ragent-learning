package com.dujiteng.ragentlearning.knowledge.processing;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.mapper.DocumentIngestionMapper;
import com.dujiteng.ragentlearning.knowledge.repository.KnowledgeRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 单实例阶段协调；数据库版本和令牌继续承担结果发布校验。删除不等待远程模型。 */
@Component
public class DocumentProcessingCoordinator {
    private final Map<Long, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
    private final KnowledgeRepository knowledge;
    private final DocumentIngestionMapper tasks;

    public DocumentProcessingCoordinator(KnowledgeRepository knowledge, DocumentIngestionMapper tasks) {
        this.knowledge = knowledge;
        this.tasks = tasks;
    }

    public <T> T manual(long userId, long baseId, long documentId, Supplier<T> action) {
        return execute(userId, baseId, documentId, false, action);
    }

    public <T> T ingestion(long userId, long baseId, long documentId, Supplier<T> action) {
        return execute(userId, baseId, documentId, true, action);
    }

    private <T> T execute(long userId, long baseId, long documentId, boolean ingestion, Supplier<T> action) {
        knowledge.findDocument(userId, baseId, documentId);
        var coordination = locks.computeIfAbsent(documentId, ignored -> new ReentrantReadWriteLock());
        if (coordination.isWriteLockedByCurrentThread()) return action.get();
        var lock = ingestion ? coordination.writeLock() : coordination.readLock();
        if (!lock.tryLock()) throw busy();
        try {
            if (!ingestion && tasks.active(documentId) != null) throw busy();
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static ChatApiException busy() {
        return new ChatApiException(409, "DOCUMENT_PROCESSING", "文档已有处理任务，请等待完成或失败后重试");
    }
}
