package com.dujiteng.ragentlearning.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("document_ingestion_task")
public record DocumentIngestionTaskDO(@TableId String id, long documentId, long userId, long knowledgeBaseId,
        String status, String stage, int chunkSize, int overlap, int attempt, String runToken,
        OffsetDateTime leaseUntil, String errorCode, String errorMessage,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
