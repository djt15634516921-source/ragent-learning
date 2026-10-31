package com.dujiteng.ragentlearning.knowledge.vo;

import java.time.Instant;

public record DocumentTextVO(long documentId, String status, String text, String errorMessage, Instant parsedAt, int characterCount) { }
