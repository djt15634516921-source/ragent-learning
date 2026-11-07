package com.dujiteng.ragentlearning.knowledge.service.impl;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.chunk.TextSplitter;
import com.dujiteng.ragentlearning.knowledge.config.KnowledgeProcessingSettings;
import com.dujiteng.ragentlearning.knowledge.dto.ChunkRequest;
import com.dujiteng.ragentlearning.knowledge.entity.DocumentIngestionTaskDO;
import com.dujiteng.ragentlearning.knowledge.mapper.DocumentIngestionMapper;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeDocumentMapper;
import com.dujiteng.ragentlearning.knowledge.processing.DocumentProcessingCoordinator;
import com.dujiteng.ragentlearning.knowledge.repository.DocumentChunkRepository;
import com.dujiteng.ragentlearning.knowledge.repository.DocumentIngestionRepository;
import com.dujiteng.ragentlearning.knowledge.repository.KnowledgeRepository;
import com.dujiteng.ragentlearning.knowledge.service.DocumentChunkService;
import com.dujiteng.ragentlearning.knowledge.service.DocumentIndexService;
import com.dujiteng.ragentlearning.knowledge.service.DocumentIngestionService;
import com.dujiteng.ragentlearning.knowledge.service.DocumentParsingService;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeService;
import com.dujiteng.ragentlearning.knowledge.vo.DocumentIngestionVO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentIngestionServiceImpl implements DocumentIngestionService {
    private static final System.Logger LOG = System.getLogger(DocumentIngestionServiceImpl.class.getName());
    private final DocumentIngestionRepository repository;
    private final DocumentIngestionMapper tasks;
    private final KnowledgeRepository knowledge;
    private final KnowledgeDocumentMapper documents;
    private final DocumentParsingService parsing;
    private final DocumentChunkService chunking;
    private final DocumentIndexService indexing;
    private final DocumentProcessingCoordinator coordinator;
    private final KnowledgeProcessingSettings settings;
    private final ExecutorService executor;
    private final long leaseMs;
    private final KnowledgeService knowledgeService;
    private final Set<String> dispatched = ConcurrentHashMap.newKeySet();
    private final Map<String, String> running = new ConcurrentHashMap<>();

    public DocumentIngestionServiceImpl(DocumentIngestionRepository repository, DocumentIngestionMapper tasks,
            KnowledgeRepository knowledge, KnowledgeDocumentMapper documents, DocumentParsingService parsing,
            DocumentChunkService chunking, DocumentIndexService indexing, DocumentProcessingCoordinator coordinator,
            KnowledgeProcessingSettings settings, KnowledgeService knowledgeService, @Qualifier("documentIngestionExecutor") ExecutorService executor,
            @Value("${knowledge.ingestion-lease-ms:15000}") long leaseMs,
            @Value("${knowledge.ingestion-poll-ms:1000}") long pollMs) {
        if (pollMs < 100 || leaseMs < pollMs * 3 || leaseMs > 300000) throw new IllegalArgumentException("入库租约须为轮询间隔的至少3倍且不超过5分钟");
        this.repository = repository; this.tasks = tasks; this.knowledge = knowledge; this.documents = documents;
        this.parsing = parsing; this.chunking = chunking; this.indexing = indexing; this.coordinator = coordinator;
        this.settings = settings; this.executor = executor; this.leaseMs = leaseMs;
        this.knowledgeService = knowledgeService;
    }

    @Override
    public DocumentIngestionVO upload(long userId, long baseId, MultipartFile file, ChunkRequest request, boolean asynchronous) {
        knowledge.requireOwner(userId, baseId);
        TextSplitter.validate(request == null || request.chunkSize() == null ? settings.chunkSize() : request.chunkSize(),
                request == null || request.overlap() == null ? settings.overlap() : request.overlap());
        if (tasks.activeCount() >= 100) throw new ChatApiException(503, "INGESTION_BUSY", "待入库任务较多，请稍后重试");
        var document = knowledgeService.upload(userId, baseId, file);
        try {
            return submit(userId, baseId, document.id(), request, asynchronous);
        } catch (RuntimeException error) {
            int status = error instanceof ChatApiException api ? api.status() : 503;
            String code = error instanceof ChatApiException api ? api.code() : "INGESTION_SUBMISSION_FAILED";
            throw new ChatApiException(status, code, "上传阶段已完成（文档编号 " + document.id()
                    + "），入库任务尚未确认，请刷新文档列表后重试一键入库", error);
        }
    }

    @Override
    public DocumentIngestionVO submit(long userId, long baseId, long documentId, ChunkRequest request, boolean asynchronous) {
        int chunkSize = request == null || request.chunkSize() == null ? settings.chunkSize() : request.chunkSize();
        int overlap = request == null || request.overlap() == null ? settings.overlap() : request.overlap();
        TextSplitter.validate(chunkSize, overlap);
        knowledge.findDocument(userId, baseId, documentId);
        var active = tasks.active(documentId);
        if (active != null) {
            if (active.chunkSize() != chunkSize || active.overlap() != overlap) {
                throw new ChatApiException(409, "INGESTION_PARAMETERS_CONFLICT", "已有任务正在使用另一组切分参数");
            }
            return status(userId, baseId, active.id());
        }
        return coordinator.ingestion(userId, baseId, documentId, () -> {
            var result = repository.submit(userId, baseId, documentId, chunkSize, overlap, !asynchronous, leaseMs);
            if (result.created() && !asynchronous) execute(result.task());
            return status(userId, baseId, result.task().id());
        });
    }

    @Override
    public DocumentIngestionVO status(long userId, long baseId, String id) {
        return DocumentIngestionVO.from(repository.requireOwned(userId, baseId, id));
    }

    @Override
    public List<DocumentIngestionVO> list(long userId, long baseId) {
        knowledge.requireOwner(userId, baseId);
        return tasks.latest(userId, baseId).stream().map(DocumentIngestionVO::from).toList();
    }

    @Override
    public DocumentIngestionVO retry(long userId, long baseId, String id) {
        var old = repository.requireOwned(userId, baseId, id);
        return coordinator.ingestion(userId, baseId, old.documentId(),
                () -> DocumentIngestionVO.from(repository.retry(userId, baseId, id)));
    }

    @Scheduled(fixedDelayString = "${knowledge.ingestion-poll-ms:1000}")
    public void dispatch() {
        for (String id : tasks.pending()) {
            if (!dispatched.add(id)) continue;
            try {
                executor.execute(() -> {
                    try {
                        String token = UUID.randomUUID().toString();
                        if (tasks.claim(id, token, leaseMs) == 1) execute(tasks.selectById(id));
                    } finally { dispatched.remove(id); }
                });
            } catch (RejectedExecutionException error) {
                dispatched.remove(id);
                // 尚未认领的任务仍在数据库中，下一轮有空位再派发。
                break;
            }
        }
    }

    @Scheduled(fixedDelayString = "${knowledge.ingestion-poll-ms:1000}")
    public void maintainLeases() {
        repository.recoverExpired();
        running.forEach((id, token) -> tasks.heartbeat(id, token, leaseMs));
    }

    private void execute(DocumentIngestionTaskDO task) {
        if (task == null) return; // 派发与删除并发时，任务已经随文档删除。
        running.put(task.id(), task.runToken());
        try {
            coordinator.ingestion(task.userId(), task.knowledgeBaseId(), task.documentId(), () -> {
                process(task);
                return null;
            });
        } catch (RuntimeException error) {
            String code = error instanceof ChatApiException api ? api.code() : "DOCUMENT_INGESTION_FAILED";
            String message = error instanceof ChatApiException api ? api.getMessage() : "入库处理失败，请检查后端运行情况后重试";
            LOG.log(System.Logger.Level.WARNING, "文档入库失败，任务 {0}，错误码 {1}，异常类型 {2}", task.id(), code, error.getClass().getName());
            tasks.fail(task.id(), task.runToken(), code, message.length() > 500 ? message.substring(0, 500) : message);
        } finally { running.remove(task.id(), task.runToken()); }
    }

    private void process(DocumentIngestionTaskDO task) {
        stage(task, "PARSING");
        // 元数据查询不含大正文；这里明确读取正文，避免把“未查询该字段”误判成“未解析”。
        var row = documents.textOwned(task.userId(), task.knowledgeBaseId(), task.documentId());
        if (row == null) throw changed();
        if (!"SUCCESS".equals(row.getStatus()) || row.getParsedText() == null) {
            parsing.parse(task.userId(), task.knowledgeBaseId(), task.documentId());
        }
        stage(task, "CHUNKING");
        row = documents.findOwned(task.userId(), task.knowledgeBaseId(), task.documentId());
        if (row == null) throw changed();
        if (!DocumentChunkRepository.isChunked(row) || row.getChunkSize() != task.chunkSize() || row.getChunkOverlap() != task.overlap()) {
            chunking.chunk(task.userId(), task.knowledgeBaseId(), task.documentId(), new ChunkRequest(task.chunkSize(), task.overlap()));
        }
        stage(task, "INDEXING");
        indexing.index(task.userId(), task.knowledgeBaseId(), task.documentId());
        if (tasks.complete(task.id(), task.runToken()) != 1) throw changed();
    }

    private void stage(DocumentIngestionTaskDO task, String stage) {
        if (Thread.currentThread().isInterrupted() || tasks.stage(task.id(), task.runToken(), stage) != 1) throw changed();
    }

    private static ChatApiException changed() {
        return new ChatApiException(409, "INGESTION_SUPERSEDED", "入库任务已删除、中断或更新，请刷新任务状态");
    }
}
