import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** 为真实 HTTP 解析验证生成有效 PDF/DOCX，不把手写字符串冒充二进制格式。 */
class ParserFixtures {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        try (var document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Ragent PDF parser: source text survives extraction.");
                content.endText();
            }
            document.save(output.resolve("text.pdf").toFile());
            var protection = new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
            protection.setEncryptionKeyLength(128);
            document.protect(protection);
            document.save(output.resolve("encrypted.pdf").toFile());
        }
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(output.resolve("no-text.pdf").toFile());
        }
        try (var document = new XWPFDocument(); var outputStream = Files.newOutputStream(output.resolve("text.docx"))) {
            document.createParagraph().createRun().setText("Ragent 中文 Word 文档：朱之文01。");
            document.createParagraph().createRun().setText("第二段用于验证段落文本能够被提取。");
            document.write(outputStream);
        }
    }
}
