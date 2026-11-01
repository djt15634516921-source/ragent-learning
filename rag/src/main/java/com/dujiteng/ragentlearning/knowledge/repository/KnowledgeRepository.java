package com.dujiteng.ragentlearning.knowledge.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.embedding.EmbeddingClient;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeBaseDO;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeDocumentDO;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeBaseMapper;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeDocumentMapper;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentTextVO;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentVO;
import com.dujiteng.ragentlearning.knowledge.vo.KnowledgeBaseVO;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 封装资源归属与 DO 到 VO 的转换，Mapper 负责实际数据库访问。 */
@Repository
public class KnowledgeRepository {
    private final EmbeddingClient embedding;
    private final KnowledgeBaseMapper bases;
    private final KnowledgeDocumentMapper documents;
    public KnowledgeRepository(KnowledgeBaseMapper bases, KnowledgeDocumentMapper documents, EmbeddingClient embedding) {
        this.embedding = embedding;
        this.bases = bases;
        this.documents = documents;
    }

    public KnowledgeBaseVO create(long userId, String name, String description) {
        var row = new KnowledgeBaseDO();
        row.setUserId(userId);
        row.setName(name);
        row.setDescription(description);
        row.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        bases.insert(row);
        return baseView(row);
    }
    public List<KnowledgeBaseVO> list(long userId) {
        return bases.selectList(new QueryWrapper<KnowledgeBaseDO>().eq("user_id", userId).orderByDesc("id"))
                .stream().map(KnowledgeRepository::baseView).toList();
    }
    public void requireOwner(long userId, long knowledgeBaseId) {
        if (bases.selectCount(new QueryWrapper<KnowledgeBaseDO>()
                .eq("id", knowledgeBaseId).eq("user_id", userId)) == 0) {
            throw new ChatApiException(404, "KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或不属于当前用户");
        }
    }
    public DocumentVO createDocument(long userId, long knowledgeBaseId, String filename,
                                     String fileType, long size, String storageKey) {
        var row = documents.createOwned(userId, knowledgeBaseId, filename, fileType, size, storageKey);
        if (row == null) {
            throw new ChatApiException(404, "KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或不属于当前用户");
        }
        return view(row);
    }
    public List<DocumentVO> listDocuments(long userId, long knowledgeBaseId) {
        requireOwner(userId, knowledgeBaseId);
        return documents.listOwned(userId, knowledgeBaseId).stream().map(this::view).toList();
    }
    public DocumentRecord findDocument(long userId, long knowledgeBaseId, long documentId) {
        var row = documents.findOwned(userId, knowledgeBaseId, documentId);
        if (row == null) throw missing();
        return new DocumentRecord(view(row), row.getStorageKey());
    }
    public boolean startParsing(long userId, long knowledgeBaseId, long documentId, String token) {
        return documents.startParsing(userId, knowledgeBaseId, documentId, token) == 1;
    }
    public boolean finishParsing(long userId, long knowledgeBaseId, long documentId,
                                  String token, String text, String error) {
        return documents.finishParsing(userId, knowledgeBaseId, documentId,
                token, text, error, error == null ? "SUCCESS" : "FAILED") == 1;
    }
    public DocumentTextVO text(long userId, long knowledgeBaseId, long documentId) {
        var row = documents.textOwned(userId, knowledgeBaseId, documentId);
        if (row == null) throw missing();
        String text = row.getParsedText();
        return new DocumentTextVO(row.getId(), row.getStatus(), text, row.getErrorMessage(),
                row.getParsedAt() == null ? null : row.getParsedAt().toInstant(),
                text == null ? 0 : text.codePointCount(0, text.length()));
    }

    private static KnowledgeBaseVO baseView(KnowledgeBaseDO row) {
        return new KnowledgeBaseVO(row.getId(), row.getName(), row.getDescription(), row.getCreatedAt().toInstant());
    }

    private DocumentVO view(KnowledgeDocumentDO row) {
        return new DocumentVO(row.getId(), row.getKnowledgeBaseId(), row.getOriginalFilename(), row.getFileType(),
                row.getFileSize(), row.getStatus(), row.getCreatedAt().toInstant(), row.getErrorMessage(),
                row.getParsedAt() == null ? null : row.getParsedAt().toInstant(),
                DocumentChunkRepository.isChunked(row) ? "SUCCESS" : "PENDING",
                DocumentChunkRepository.isChunked(row) ? row.getChunkCount() : 0,
                DocumentChunkRepository.isChunked(row) && row.getChunkedAt() != null ? row.getChunkedAt().toInstant() : null,
                DocumentVectorRepository.ready(row, embedding.profile()) ? "SUCCESS"
                    : "SUCCESS".equals(row.getIndexStatus()) ? "PENDING" : row.getIndexStatus(),
                DocumentVectorRepository.ready(row, embedding.profile()) ? row.getIndexCount() : 0, row.getIndexError());
    }

    private static ChatApiException missing() {
        return new ChatApiException(404, "DOCUMENT_NOT_FOUND", "文档不存在或不属于当前用户");
    }

    /** 内部使用的下载/解析信息；storageKey 不进入 HTTP VO。 */
    public record DocumentRecord(DocumentVO view, String storageKey) { }
}
