package com.dujiteng.ragentlearning.knowledge.service.impl;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeLifecycleMapper;
import com.dujiteng.ragentlearning.knowledge.service.FileCleanupService;
import com.dujiteng.ragentlearning.knowledge.storage.FileStorage;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class FileCleanupServiceImpl implements FileCleanupService {
    private static final System.Logger LOG = System.getLogger(FileCleanupServiceImpl.class.getName());
    private final KnowledgeLifecycleMapper mapper;
    private final FileStorage storage;

    public FileCleanupServiceImpl(KnowledgeLifecycleMapper mapper, FileStorage storage) {
        this.mapper = mapper;
        this.storage = storage;
    }

    @Override
    public int clean(List<Long> ids) {
        for (long id : ids) {
            String key = mapper.cleanupKey(id);
            if (key == null) continue;
            try {
                storage.delete(key);
            } catch (ChatApiException error) {
                mapper.failCleanup(id, error.code());
                LOG.log(System.Logger.Level.WARNING, "原文件待重试清理，任务 {0}，错误码 {1}", id, error.code());
                continue;
            }
            mapper.finishCleanup(id);
        }
        return ids.isEmpty() ? 0 : mapper.countCleanup(ids);
    }

    @Scheduled(fixedDelayString = "${knowledge.cleanup-poll-ms:30000}")
    public void retryPending() {
        clean(mapper.pendingCleanup());
    }
}
