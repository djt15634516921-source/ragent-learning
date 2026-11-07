package com.dujiteng.ragentlearning.knowledge.service.impl;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.processing.DocumentProcessingCoordinator;
import com.dujiteng.ragentlearning.knowledge.parse.DocumentParser;
import com.dujiteng.ragentlearning.knowledge.repository.KnowledgeRepository;
import com.dujiteng.ragentlearning.knowledge.service.DocumentParsingService;
import com.dujiteng.ragentlearning.knowledge.storage.FileStorage;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentTextVO;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 组织归属校验、状态转换、解析器调用和结果落库。解析时不持有数据库事务。 */
@Service
public class DocumentParsingServiceImpl implements DocumentParsingService {
    private static final System.Logger LOG = System.getLogger(DocumentParsingService.class.getName());
    private final DocumentProcessingCoordinator coordinator;
    private final KnowledgeRepository repository;
    private final FileStorage storage;
    private final List<DocumentParser> parsers;

    public DocumentParsingServiceImpl(KnowledgeRepository repository, FileStorage storage, List<DocumentParser> parsers, DocumentProcessingCoordinator coordinator) {
        this.coordinator = coordinator;
        this.repository = repository;
        this.storage = storage;
        this.parsers = parsers;
    }

    @Override
    public DocumentTextVO parse(long userId, long knowledgeBaseId, long documentId) {
        return coordinator.manual(userId, knowledgeBaseId, documentId, () -> parseDocument(userId, knowledgeBaseId, documentId));
    }

    private DocumentTextVO parseDocument(long userId, long knowledgeBaseId, long documentId) {
        var document = database(() -> repository.findDocument(userId, knowledgeBaseId, documentId));
        String token = UUID.randomUUID().toString();
        if (!database(() -> repository.startParsing(userId, knowledgeBaseId, documentId, token))) {
            throw new ChatApiException(409, "DOCUMENT_PROCESSING", "文档正在解析，请稍后刷新；中断的任务五分钟后可重试");
        }
        String text;
        try {
            DocumentParser parser = parsers.stream().filter(p -> p.supports(document.view().fileType())).findFirst()
                    .orElseThrow(() -> new ChatApiException(422, "DOCUMENT_PARSER_NOT_FOUND", "当前文件类型没有可用解析器"));
            text = parser.parse(storage.load(document.storageKey()), document.view().fileType());
        } catch (RuntimeException error) {
            ChatApiException failure = error instanceof ChatApiException known ? known
                    : new ChatApiException(422, "DOCUMENT_PARSE_FAILED", "文档解析失败，请检查原文件格式", error);
            LOG.log(System.Logger.Level.WARNING, "文档解析失败，编号：{0}，错误码：{1}", documentId, failure.code());
            finish(userId, knowledgeBaseId, documentId, token, null, failure.getMessage());
            throw failure;
        }
        // 成功状态和完整文本由同一条 UPDATE 写入，不保存被截断的部分正文。
        finish(userId, knowledgeBaseId, documentId, token, text, null);
        return text(userId, knowledgeBaseId, documentId);
    }

    @Override
    public DocumentTextVO text(long userId, long knowledgeBaseId, long documentId) {
        return database(() -> repository.text(userId, knowledgeBaseId, documentId));
    }

    private void finish(long userId, long knowledgeBaseId, long documentId, String token, String text, String error) {
        if (!database(() -> repository.finishParsing(userId, knowledgeBaseId, documentId, token, text, error))) {
            throw new ChatApiException(409, "DOCUMENT_PARSE_SUPERSEDED", "解析任务已更新，请刷新查看最新结果");
        }
    }

    private static <T> T database(Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException error) {
            LOG.log(System.Logger.Level.ERROR, "解析状态或结果写入失败，异常类型：{0}", error.getClass().getName());
            throw new ChatApiException(503, "KNOWLEDGE_DATABASE_UNAVAILABLE", "无法读写解析状态，请检查数据库后刷新确认结果", error);
        }
    }
}
