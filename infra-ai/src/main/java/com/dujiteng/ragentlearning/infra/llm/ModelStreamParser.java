package com.dujiteng.ragentlearning.infra.llm;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** 读取模型 SSE 事件，从 choices[0].delta.content 中取出新增文字。 */
final class ModelStreamParser {

    private static final int MAX_EVENT_CHARS = 262_144;
    private static final int MAX_ANSWER_CHARS = 64_000;

    private ModelStreamParser() {
    }

    static String read(BufferedReader reader, Function<String, Map<String, Object>> decode,
                       Consumer<String> onDelta, StreamCancellation cancellation) throws IOException {
        StringBuilder data = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        String line;
        boolean firstLine = true;
        while ((line = reader.readLine()) != null) {
            cancellation.check();
            if (firstLine && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            firstLine = false;
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    if (accept(data.toString(), decode, onDelta, answer, cancellation)) {
                        return completedAnswer(answer);
                    }
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value);
                if (data.length() > MAX_EVENT_CHARS) {
                    throw invalid("模型单个流式事件过大");
                }
            }
            // 注释、event、id 等 SSE 字段不作为答案正文。
        }
        cancellation.check();
        // 容许最后的 [DONE] 后没有额外空行；普通 EOF 不能视作成功。
        if (data.length() > 0 && accept(data.toString(), decode, onDelta, answer, cancellation)) {
            return completedAnswer(answer);
        }
        throw new ChatApiException(502, "MODEL_STREAM_INCOMPLETE",
                "模型连接结束，但没有收到完整结束标记；本轮未保存，请重试");
    }

    private static boolean accept(String data, Function<String, Map<String, Object>> decode,
                                  Consumer<String> onDelta, StringBuilder answer,
                                  StreamCancellation cancellation) {
        cancellation.check();
        if ("[DONE]".equals(data.strip())) {
            return true;
        }
        Map<String, Object> body;
        try {
            body = decode.apply(data);
        } catch (RuntimeException error) {
            throw invalid("模型流式事件无法解析为 JSON");
        }
        if (body == null || body.containsKey("error")) {
            throw invalid("模型返回了无效事件或流式错误");
        }
        Object rawChoices = body.get("choices");
        if (!(rawChoices instanceof List<?> choices)) {
            throw invalid("模型流式事件缺少 choices");
        }
        if (choices.isEmpty()) {
            return false; // 例如仅包含 usage 的尾部事件。
        }
        if (!(choices.get(0) instanceof Map<?, ?> choice)) {
            throw invalid("模型流式事件的 choice 格式不正确");
        }
        Object finishReason = choice.get("finish_reason");
        if (finishReason != null && !"stop".equals(finishReason)) {
            throw new ChatApiException(502, "MODEL_STREAM_NOT_FINISHED",
                    "模型未正常完成回答，可能达到输出上限或触发平台限制；本轮未保存");
        }
        if (!(choice.get("delta") instanceof Map<?, ?> delta)) {
            throw invalid("模型流式事件缺少 delta");
        }
        Object content = delta.get("content");
        if (content == null) {
            return false; // 首包只有 role，或包中只有 reasoning_content。
        }
        if (!(content instanceof String text)) {
            throw invalid("模型流式正文不是字符串");
        }
        if (!text.isEmpty()) {
            if (answer.length() + text.length() > MAX_ANSWER_CHARS) {
                throw new ChatApiException(502, "MODEL_ANSWER_TOO_LONG", "回答超过本课的长度上限，本轮未保存");
            }
            // 不能用 isBlank 过滤：独立的空格、换行也是正文的一部分。
            answer.append(text);
            onDelta.accept(text);
        }
        return false;
    }

    private static String completedAnswer(StringBuilder answer) {
        String text = answer.toString().strip();
        if (text.isEmpty()) {
            throw new ChatApiException(502, "MODEL_ANSWER_EMPTY", "模型没有返回有效的回答正文");
        }
        return text;
    }

    private static ChatApiException invalid(String message) {
        return new ChatApiException(502, "MODEL_STREAM_INVALID", message);
    }
}
