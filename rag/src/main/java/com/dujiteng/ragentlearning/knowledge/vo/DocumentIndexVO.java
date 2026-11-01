package com.dujiteng.ragentlearning.knowledge.vo;

import java.time.Instant;

public record DocumentIndexVO(long documentId, String status, int count, String model,
        String errorMessage, Instant indexedAt) { }
