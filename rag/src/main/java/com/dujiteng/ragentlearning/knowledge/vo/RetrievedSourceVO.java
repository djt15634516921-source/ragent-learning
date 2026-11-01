package com.dujiteng.ragentlearning.knowledge.vo;

/** 每次回答的证据快照；编号按本次检索顺序从 1 开始引用。 */
public record RetrievedSourceVO(long chunkId, long documentId, String filename, int chunkIndex,
        int startOffset, int endOffset, String content, double score, long sourceVersion) { }
