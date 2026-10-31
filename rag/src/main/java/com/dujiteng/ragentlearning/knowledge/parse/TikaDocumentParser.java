package com.dujiteng.ragentlearning.knowledge.parse;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.io.IOException;
import java.io.InputStream;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

@Component
public class TikaDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String fileType) { return "pdf".equals(fileType) || "docx".equals(fileType); }

    @Override
    public String parse(Resource resource, String fileType) {
        Parser parser = "pdf".equals(fileType) ? new PDFParser() : new OOXMLParser();
        ParseContext context = new ParseContext();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdfConfig);
        // 当前只提取正文，不递归打开嵌入附件。
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override public boolean shouldParseEmbedded(Metadata metadata) { return false; }
            @Override public void parseEmbedded(InputStream input, ContentHandler handler, Metadata metadata, boolean html) { }
        });
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_CHARS);
        try (var input = resource.getInputStream()) {
            parser.parse(input, handler, new Metadata(), context);
            return DocumentParser.normalize(handler.toString());
        } catch (EncryptedDocumentException error) {
            throw new ChatApiException(422, "DOCUMENT_ENCRYPTED", "文件已加密，请提供未加密版本", error);
        } catch (IOException | TikaException | SAXException error) {
            if (WriteLimitReachedException.isWriteLimitReached(error)) {
                throw new ChatApiException(422, "DOCUMENT_TEXT_TOO_LARGE", "提取文本超过 100 万字符，请拆分文档后重试", error);
            }
            throw new ChatApiException(422, "DOCUMENT_PARSE_FAILED", "文件无法解析，请检查是否损坏、加密或与扩展名不符", error);
        }
    }
}
