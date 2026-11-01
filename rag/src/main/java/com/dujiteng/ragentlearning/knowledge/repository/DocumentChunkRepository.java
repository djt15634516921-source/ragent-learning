package com.dujiteng.ragentlearning.knowledge.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.chunk.TextSplitter.Segment;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeChunkDO;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeDocumentDO;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeChunkMapper;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeDocumentMapper;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentChunkVO;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentChunksVO;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 切分持久化边界：版本检查、整批替换与统计同一事务提交。 */
@Repository
public class DocumentChunkRepository {
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeChunkMapper chunks;

    public DocumentChunkRepository(KnowledgeDocumentMapper documents, KnowledgeChunkMapper chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    public KnowledgeDocumentDO source(long userId, long knowledgeBaseId, long documentId) {
        var row = require(documents.textOwned(userId, knowledgeBaseId, documentId));
        requireParsed(row);
        return row;
    }

    @Transactional
    public void replace(long userId, long knowledgeBaseId, long documentId, long expectedVersion,
                        int chunkSize, int overlap, List<Segment> segments) {
        var row = require(documents.lockOwned(userId, knowledgeBaseId, documentId));
        if (!"SUCCESS".equals(row.getStatus()) || row.getParseVersion() != expectedVersion) {
            throw new ChatApiException(409, "DOCUMENT_CHANGED", "原文已更新或正在重新解析，请刷新后重新切分");
        }
        if (isChunked(row) && Objects.equals(row.getChunkSize(), chunkSize)
                && Objects.equals(row.getChunkOverlap(), overlap)) return;
        chunks.delete(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getDocumentId, documentId));
        List<KnowledgeChunkDO> rows = segments.stream().map(segment -> {
            var chunk = new KnowledgeChunkDO();
            chunk.setDocumentId(documentId);
            chunk.setSourceVersion(expectedVersion);
            chunk.setChunkIndex(segment.index());
            chunk.setContent(segment.content());
            chunk.setStartOffset(segment.startOffset());
            chunk.setEndOffset(segment.endOffset());
            chunk.setCharacterCount(segment.endOffset() - segment.startOffset());
            return chunk;
        }).toList();
        // 控制单条 SQL 参数数量；所有批次仍处于同一个 Spring 事务。
        for (int start = 0; start < rows.size(); start += 200) {
            List<KnowledgeChunkDO> batch = rows.subList(start, Math.min(start + 200, rows.size()));
            if (chunks.insertBatch(batch) != batch.size()) throw new IllegalStateException("片段批次写入数量不一致");
        }
        if (documents.markChunked(userId, knowledgeBaseId, documentId, expectedVersion, chunkSize, overlap, rows.size()) != 1) {
            throw new ChatApiException(409, "DOCUMENT_CHANGED", "原文已更新，请刷新后重新切分");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DocumentChunksVO list(long userId, long knowledgeBaseId, long documentId, int page, int size) {
        // 短只读事务确保页内正文与来源元数据属于同一版本。
        var row = require(documents.findOwned(userId, knowledgeBaseId, documentId));
        if (!isChunked(row)) return view(row, page, size, 0, List.of());
        var query = new Page<KnowledgeChunkDO>(page, size);
        query.setOptimizeCountSql(false);
        var result = chunks.selectOwnedPage(query, userId, knowledgeBaseId, documentId);
        List<DocumentChunkVO> records = result.getRecords().stream().map(chunk -> new DocumentChunkVO(
                chunk.getId(), chunk.getDocumentId(), chunk.getChunkIndex(), chunk.getContent(),
                chunk.getCharacterCount(), chunk.getStartOffset(), chunk.getEndOffset())).toList();
        return view(row, page, size, result.getTotal(), records);
    }

    public static boolean isChunked(KnowledgeDocumentDO row) {
        return "SUCCESS".equals(row.getStatus()) && row.getChunkVersion() != null
                && row.getChunkVersion().equals(row.getParseVersion()) && row.getChunkCount() > 0;
    }

    private static DocumentChunksVO view(KnowledgeDocumentDO row, int page, int size,
                                          long total, List<DocumentChunkVO> records) {
        boolean ready = isChunked(row);
        return new DocumentChunksVO(row.getId(), row.getOriginalFilename(), row.getParseVersion(), row.getStatus(),
                ready ? "SUCCESS" : "PENDING", ready ? row.getChunkSize() : null,
                ready ? row.getChunkOverlap() : null,
                ready && row.getChunkedAt() != null ? row.getChunkedAt().toInstant() : null,
                total, page, size, records);
    }

    private static KnowledgeDocumentDO require(KnowledgeDocumentDO row) {
        if (row == null) throw new ChatApiException(404, "DOCUMENT_NOT_FOUND", "文档不存在或不属于当前用户");
        return row;
    }

    private static void requireParsed(KnowledgeDocumentDO row) {
        if (!"SUCCESS".equals(row.getStatus()) || row.getParsedText() == null) {
            throw new ChatApiException(409, "DOCUMENT_NOT_PARSED", "请先成功解析文档，再切分文本");
        }
    }
}
