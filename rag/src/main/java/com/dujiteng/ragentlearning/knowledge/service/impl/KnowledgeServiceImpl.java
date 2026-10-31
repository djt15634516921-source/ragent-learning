package com.dujiteng.ragentlearning.knowledge.service.impl;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.repository.KnowledgeRepository;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeService;
import com.dujiteng.ragentlearning.knowledge.storage.FileStorage;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentDownload;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentVO;
import com.dujiteng.ragentlearning.knowledge.vo.KnowledgeBaseVO;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeServiceImpl implements KnowledgeService {
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "pdf", "docx");
    private static final System.Logger LOG = System.getLogger(KnowledgeService.class.getName());
    private final KnowledgeRepository repository;
    private final FileStorage storage;

    public KnowledgeServiceImpl(KnowledgeRepository repository, FileStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Override
    public KnowledgeBaseVO create(long userId, String name, String description) {
        String title = name == null ? "" : name.strip();
        String detail = description == null ? "" : description.strip();
        if (title.isBlank() || title.length() > 100 || detail.length() > 500) {
            throw new ChatApiException(400, "KNOWLEDGE_BASE_INVALID", "名称需要 1～100 个字符，说明最多 500 个字符");
        }
        return database(() -> repository.create(userId, title, detail));
    }

    @Override
    public List<KnowledgeBaseVO> list(long userId) {
        return database(() -> repository.list(userId));
    }

    @Override
    public List<DocumentVO> documents(long userId, long knowledgeBaseId) {
        return database(() -> repository.listDocuments(userId, knowledgeBaseId));
    }

    @Override
    public DocumentVO upload(long userId, long knowledgeBaseId, MultipartFile file) {
        // 先确定归属，再校验、存文件。越权请求不能写入文件存储。
        database(() -> { repository.requireOwner(userId, knowledgeBaseId); return null; });
        if (file == null || file.isEmpty()) {
            throw new ChatApiException(400, "FILE_REQUIRED", "请选择一个非空文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ChatApiException(413, "FILE_TOO_LARGE", "单个文件不能超过 10 MB");
        }
        String filename = safeFilename(file.getOriginalFilename());
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) {
            throw new ChatApiException(415, "FILE_TYPE_UNSUPPORTED", "当前支持 TXT、Markdown、PDF 和 DOCX 文件");
        }
        FileStorage.StoredFile stored = storage.store(userId, knowledgeBaseId, file, extension);
        try {
            return database(() -> repository.createDocument(userId, knowledgeBaseId, filename,
                    extension, stored.size(), stored.key()));
        } catch (RuntimeException error) {
            // 数据库与文件系统没有共同事务；明确的写库失败时清理刚保存的原文件。
            try { storage.delete(stored.key()); }
            catch (RuntimeException cleanup) {
                error.addSuppressed(cleanup);
                LOG.log(System.Logger.Level.ERROR, "未入库文件清理失败，存储编号：{0}", stored.key());
            }
            throw error;
        }
    }

    @Override
    public DocumentDownload download(long userId, long knowledgeBaseId, long documentId) {
        var document = database(() -> repository.findDocument(userId, knowledgeBaseId, documentId));
        return new DocumentDownload(document.view(), storage.load(document.storageKey()));
    }

    private static String safeFilename(String original) {
        String normalized = original == null ? "" : original.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (filename.isBlank() || filename.length() > 255 || filename.codePoints().anyMatch(Character::isISOControl)) {
            throw new ChatApiException(400, "FILENAME_INVALID", "文件名不能为空、包含控制字符或超过 255 个字符");
        }
        return filename;
    }

    private static <T> T database(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException error) {
            LOG.log(System.Logger.Level.ERROR, "知识库数据库操作失败，异常类型：{0}", error.getClass().getName());
            throw new ChatApiException(503, "KNOWLEDGE_DATABASE_UNAVAILABLE", "知识库数据访问失败，请检查数据库", error);
        }
    }

}
