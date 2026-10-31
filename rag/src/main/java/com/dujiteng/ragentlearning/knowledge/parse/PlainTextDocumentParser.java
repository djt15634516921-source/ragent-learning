package com.dujiteng.ragentlearning.knowledge.parse;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class PlainTextDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String fileType) { return "txt".equals(fileType) || "md".equals(fileType); }

    @Override
    public String parse(Resource resource, String fileType) {
        try (var input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(10 * 1024 * 1024 + 1);
            if (bytes.length > 10 * 1024 * 1024) {
                throw new ChatApiException(413, "FILE_TOO_LARGE", "原文件超过 10 MB，请拆分后上传");
            }
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return DocumentParser.normalize(text);
        } catch (CharacterCodingException error) {
            throw new ChatApiException(422, "DOCUMENT_ENCODING_INVALID", "TXT 和 Markdown 请另存为 UTF-8 编码后重新上传", error);
        } catch (IOException error) {
            throw new ChatApiException(503, "DOCUMENT_READ_FAILED", "读取原文件失败，请检查文件存储", error);
        }
    }
}
