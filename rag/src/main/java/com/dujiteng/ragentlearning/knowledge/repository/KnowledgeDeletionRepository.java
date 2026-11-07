package com.dujiteng.ragentlearning.knowledge.repository;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeDocumentMapper;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeLifecycleMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KnowledgeDeletionRepository {
    private final KnowledgeLifecycleMapper lifecycle;
    private final KnowledgeDocumentMapper documents;

    public KnowledgeDeletionRepository(KnowledgeLifecycleMapper lifecycle, KnowledgeDocumentMapper documents) {
        this.lifecycle = lifecycle;
        this.documents = documents;
    }

    @Transactional
    public Deleted deleteDocument(long userId, long baseId, long documentId) {
        lockBase(userId, baseId);
        var row = documents.lockOwned(userId, baseId, documentId);
        if (row == null) throw new ChatApiException(404, "DOCUMENT_NOT_FOUND", "文档不存在或不属于当前用户");
        long cleanup = lifecycle.enqueueCleanup(row.getStorageKey());
        lifecycle.deleteDocument(baseId, documentId);
        return new Deleted(1, List.of(cleanup));
    }

    @Transactional
    public Deleted deleteBase(long userId, long baseId) {
        lockBase(userId, baseId);
        var rows = lifecycle.lockDocuments(baseId);
        List<Long> cleanup = new ArrayList<>();
        for (var row : rows) {
            cleanup.add(lifecycle.enqueueCleanup(row.getStorageKey()));
            // 外键级联清理片段、向量和入库任务；已保存的聊天来源 JSON 不引用这些行。
            lifecycle.deleteDocument(baseId, row.getId());
        }
        lifecycle.deleteBase(baseId);
        return new Deleted(rows.size(), List.copyOf(cleanup));
    }

    private void lockBase(long userId, long baseId) {
        if (lifecycle.lockBase(userId, baseId) == null) {
            throw new ChatApiException(404, "KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或不属于当前用户");
        }
    }

    public record Deleted(int documents, List<Long> cleanupIds) { }
}
