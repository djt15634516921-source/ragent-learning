package com.dujiteng.ragentlearning.knowledge.vo;

public record DocumentChunkVO(long id, long documentId, int chunkIndex, String content,
                              int characterCount, int startOffset, int endOffset) { }
