package com.dujiteng.ragentlearning.knowledge.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeChunkDO;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeDocumentDO;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeVectorDO;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeChunkMapper;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeDocumentMapper;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeVectorMapper;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentIndexVO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 远程计算在事务外；认领任务和整批发布分别使用短事务。 */
@Repository
public class DocumentVectorRepository {
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeChunkMapper chunks;
    private final KnowledgeVectorMapper vectors;

    public DocumentVectorRepository(KnowledgeDocumentMapper documents, KnowledgeChunkMapper chunks, KnowledgeVectorMapper vectors) {
        this.documents = documents;
        this.chunks = chunks;
        this.vectors = vectors;
    }

    public DocumentIndexVO status(long userId, long knowledgeBaseId, long documentId, String profile) {
        return view(require(documents.findOwned(userId, knowledgeBaseId, documentId)), profile);
    }

    @Transactional
    public Task begin(long userId, long knowledgeBaseId, long documentId, String profile, int maximumChunks) {
        var row = require(documents.lockOwned(userId, knowledgeBaseId, documentId));
        if (!DocumentChunkRepository.isChunked(row)) {
            throw new ChatApiException(409, "DOCUMENT_NOT_CHUNKED", "请先成功解析并切分文档");
        }
        if (ready(row, profile)) return null;
        if ("RUNNING".equals(row.getIndexStatus()) && row.getIndexStartedAt() != null
                && row.getIndexStartedAt().isAfter(OffsetDateTime.now().minusMinutes(5))) {
            throw new ChatApiException(409, "DOCUMENT_INDEX_RUNNING", "文档正在向量化，请稍后刷新");
        }
        if (row.getChunkCount() > maximumChunks) {
            throw new ChatApiException(400, "DOCUMENT_INDEX_TOO_LARGE", "本次学习版本每份文档最多向量化 " + maximumChunks + " 个片段");
        }
        var source = chunks.selectList(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocumentId, documentId).eq(KnowledgeChunkDO::getSourceVersion, row.getParseVersion())
                .orderByAsc(KnowledgeChunkDO::getChunkIndex));
        if (source.size() != row.getChunkCount()) throw changed();
        String token = UUID.randomUUID().toString();
        vectors.claim(documentId, token);
        return new Task(userId, knowledgeBaseId, documentId, row.getParseVersion(), row.getChunkRevision(), token, List.copyOf(source));
    }

    public void renew(Task task) {
        if (vectors.renew(task.documentId(), task.token(), task.revision()) != 1) throw changed();
    }

    @Transactional
    public void finish(Task task, String model, String profile, List<String> embeddings) {
        var row = require(documents.lockOwned(task.userId(), task.knowledgeBaseId(), task.documentId()));
        if (!DocumentChunkRepository.isChunked(row) || !Objects.equals(row.getIndexToken(), task.token())
                || row.getParseVersion() != task.version() || row.getChunkRevision() != task.revision()) throw changed();
        if (embeddings.size() != task.chunks().size()) throw new IllegalArgumentException("向量和片段数量不一致");
        vectors.deleteDocument(task.documentId());
        var rows = new java.util.ArrayList<KnowledgeVectorDO>();
        for (int i = 0; i < embeddings.size(); i++) {
            var vector = new KnowledgeVectorDO();
            vector.setChunkId(task.chunks().get(i).getId());
            vector.setEmbedding(embeddings.get(i));
            rows.add(vector);
        }
        for (int start = 0; start < rows.size(); start += 200) {
            var batch = rows.subList(start, Math.min(start + 200, rows.size()));
            if (vectors.insertBatch(batch, task.version(), task.revision(), profile, model) != batch.size()) {
                throw new IllegalStateException("向量写入数量不一致");
            }
        }
        if (vectors.complete(task.documentId(), task.token(), task.revision(), model, profile, rows.size()) != 1) throw changed();
    }

    public void fail(Task task, String error) { vectors.fail(task.documentId(), task.token(), error); }

    public static boolean ready(KnowledgeDocumentDO row, String profile) {
        return DocumentChunkRepository.isChunked(row) && "SUCCESS".equals(row.getIndexStatus())
                && Objects.equals(row.getChunkRevision(), row.getIndexedRevision())
                && Objects.equals(profile, row.getIndexProfile()) && Objects.equals(row.getIndexCount(), row.getChunkCount());
    }

    private static DocumentIndexVO view(KnowledgeDocumentDO row, String profile) {
        boolean ready = ready(row, profile);
        String status = ready ? "SUCCESS" : "SUCCESS".equals(row.getIndexStatus()) ? "PENDING" : row.getIndexStatus();
        return new DocumentIndexVO(row.getId(), status, ready ? row.getIndexCount() : 0,
                row.getIndexModel(), row.getIndexError(), ready && row.getIndexedAt() != null ? row.getIndexedAt().toInstant() : null);
    }

    private static KnowledgeDocumentDO require(KnowledgeDocumentDO row) {
        if (row == null) throw new ChatApiException(404, "DOCUMENT_NOT_FOUND", "文档不存在或不属于当前用户");
        return row;
    }

    private static ChatApiException changed() {
        return new ChatApiException(409, "DOCUMENT_CHANGED", "文档或切分已更新，请刷新后重新向量化");
    }

    public record Task(long userId, long knowledgeBaseId, long documentId, long version, long revision, String token, List<KnowledgeChunkDO> chunks) { }
}
