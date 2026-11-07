package com.dujiteng.ragentlearning.knowledge.vo;

public record KnowledgeSearchSettingsVO(int topK, double minScore, int maxTopK, int maxContextCharacters,
                                       int chunkSize, int overlap, String embeddingModel,
                                       int embeddingDimensions, String embeddingProfile, int recallBudget,
                                       int candidateLimit, boolean rerankEnabled, String rerankModel,
                                       int maxRerankInputCharacters) { }
