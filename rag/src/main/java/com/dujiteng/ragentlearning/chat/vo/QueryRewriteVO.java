package com.dujiteng.ragentlearning.chat.vo;

import com.dujiteng.ragentlearning.rag.core.rewrite.RewriteResult;

/** 仅描述本轮检索输入；不是证据，也不改写数据库里的用户原话。 */
public record QueryRewriteVO(String originalQuestion, String searchQuestion, String status, long durationMs) {
    public static QueryRewriteVO from(RewriteResult result) {
        return result == null ? null : new QueryRewriteVO(result.originalQuestion(), result.searchQuestion(),
                result.status(), result.durationMs());
    }
}
