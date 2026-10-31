package com.dujiteng.ragentlearning.knowledge.parse;

import com.dujiteng.ragentlearning.common.ChatApiException;
import org.springframework.core.io.Resource;

/** 解析器只负责从原文件提取文本，不负责数据库状态或访问权限。 */
public interface DocumentParser {
    int MAX_TEXT_CHARS = 1_000_000;
    boolean supports(String fileType);
    String parse(Resource resource, String fileType);

    static String normalize(String raw) {
        String text = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        text = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.length() > MAX_TEXT_CHARS) {
            throw new ChatApiException(422, "DOCUMENT_TEXT_TOO_LARGE", "提取文本超过 100 万字符，请拆分文档后重试");
        }
        if (text.isBlank()) {
            throw new ChatApiException(422, "DOCUMENT_TEXT_EMPTY", "没有提取到可用文字；扫描图片型 PDF 需要后续 OCR 支持");
        }
        if (text.indexOf('\0') >= 0) {
            throw new ChatApiException(422, "DOCUMENT_TEXT_INVALID", "文本包含无效字符，请检查原文件格式或编码");
        }
        return text;
    }
}
