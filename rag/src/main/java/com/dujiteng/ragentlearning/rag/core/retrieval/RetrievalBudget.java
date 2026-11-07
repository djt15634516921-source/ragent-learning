package com.dujiteng.ragentlearning.rag.core.retrieval;

/** 参考原项目：召回、送精排的候选池、最终上下文分别预算。 */
public record RetrievalBudget(int recallBudget, int candidateLimit, int contextTopK) {
    public RetrievalBudget {
        if (contextTopK < 1 || contextTopK > 20 || candidateLimit < contextTopK
                || recallBudget < candidateLimit || recallBudget > 100) {
            throw new IllegalArgumentException("检索数量须满足1≤上下文≤候选池≤召回≤100，且上下文最多20条");
        }
    }
}
