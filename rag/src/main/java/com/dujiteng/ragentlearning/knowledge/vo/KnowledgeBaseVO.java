package com.dujiteng.ragentlearning.knowledge.vo;

import java.time.Instant;

public record KnowledgeBaseVO(long id, String name, String description, Instant createdAt) { }
