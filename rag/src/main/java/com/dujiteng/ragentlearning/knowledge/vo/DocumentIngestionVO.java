package com.dujiteng.ragentlearning.knowledge.vo;

import com.dujiteng.ragentlearning.knowledge.entity.DocumentIngestionTaskDO;
import java.time.Instant;

public record DocumentIngestionVO(String taskId, long documentId, long knowledgeBaseId, String status,
        String stage, int attempt, int chunkSize, int overlap, String errorCode, String errorMessage,
        Instant createdAt, Instant updatedAt) {
    public static DocumentIngestionVO from(DocumentIngestionTaskDO task) {
        return new DocumentIngestionVO(task.id(), task.documentId(), task.knowledgeBaseId(), task.status(),
                task.stage(), task.attempt(), task.chunkSize(), task.overlap(), task.errorCode(), task.errorMessage(),
                task.createdAt().toInstant(), task.updatedAt().toInstant());
    }
}
