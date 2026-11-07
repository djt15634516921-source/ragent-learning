package com.dujiteng.ragentlearning.knowledge.config;

import com.dujiteng.ragentlearning.knowledge.chunk.TextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeProcessingSettings {
    private final int chunkSize;
    private final int overlap;

    public KnowledgeProcessingSettings(@Value("${knowledge.chunk-size:800}") int chunkSize,
                                       @Value("${knowledge.chunk-overlap:120}") int overlap) {
        TextSplitter.validate(chunkSize, overlap);
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public int chunkSize() { return chunkSize; }
    public int overlap() { return overlap; }
}
