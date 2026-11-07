package com.dujiteng.ragentlearning.knowledge.service.impl;

import com.dujiteng.ragentlearning.knowledge.repository.KnowledgeDeletionRepository;
import com.dujiteng.ragentlearning.knowledge.service.FileCleanupService;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeDeletionService;
import com.dujiteng.ragentlearning.knowledge.vo.DeletionResultVO;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeDeletionServiceImpl implements KnowledgeDeletionService {
    private final KnowledgeDeletionRepository repository;
    private final FileCleanupService cleanup;

    public KnowledgeDeletionServiceImpl(KnowledgeDeletionRepository repository, FileCleanupService cleanup) {
        this.repository = repository;
        this.cleanup = cleanup;
    }

    @Override
    public DeletionResultVO deleteDocument(long userId, long baseId, long documentId) {
        return finish(repository.deleteDocument(userId, baseId, documentId));
    }

    @Override
    public DeletionResultVO deleteBase(long userId, long baseId) {
        return finish(repository.deleteBase(userId, baseId));
    }

    private DeletionResultVO finish(KnowledgeDeletionRepository.Deleted result) {
        // 独立 Repository 代理已经提交；清理故障保留队列，不把业务记录重新恢复成可访问。
        return new DeletionResultVO(result.documents(), cleanup.clean(result.cleanupIds()));
    }
}
