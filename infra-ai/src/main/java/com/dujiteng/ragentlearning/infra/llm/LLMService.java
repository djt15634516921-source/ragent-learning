package com.dujiteng.ragentlearning.infra.llm;

import java.util.function.Consumer;

/** 业务通过统一入口访问模型，调用预算和生成参数随请求传递。 */
public interface LLMService {
    String chat(LLMRequest request, StreamCancellation cancellation);
    String streamChat(LLMRequest request, Consumer<String> onDelta, StreamCancellation cancellation);
}
