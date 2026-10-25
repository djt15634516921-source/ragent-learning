package com.dujiteng.ragentlearning.chat;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.ModelClient;
import org.springframework.stereotype.Service;

/** 业务层：校验和整理用户问题，再请求模型客户端。 */
@Service
public class ChatService {

    private static final int MAX_QUESTION_CHARS = 8_000;
    private final ModelClient modelClient;
    private static final String SYSTEM_PROMPT =
            "你是一名Java后端学习助手，请用中文回答。"
                    + "回答第一行固定为【Ragent学习助手】。"
                    + "随后给出简短解释和一个具体例子。";

    public ChatService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public String chat(String question) {
        if (question == null || question.isBlank()) {
            throw new ChatApiException(400, "QUESTION_REQUIRED", "question 不能为空,输入问题");
        }
        String normalized = question.strip();
        if (normalized.length() > MAX_QUESTION_CHARS) {
            throw new ChatApiException(400, "QUESTION_TOO_LONG", "本课接口的问题长度上限为 8000 个字符");
        }
        return modelClient.chat(SYSTEM_PROMPT, normalized);
    }
}
