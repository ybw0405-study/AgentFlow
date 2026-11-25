package com.agentflow.workflow.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentflow.common.error.BusinessException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/** 文档解析：纯文本、Word/PDF 往返、不支持类型。 */
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parsesPlainTextAndMarksSource() {
        DocumentParser.ParsedDocument parsed =
                parser.parse("note.txt", "第一行\n第二行".getBytes(StandardCharsets.UTF_8));
        assertEquals("第一行\n第二行", parsed.text());
        assertEquals("TXT", parsed.source());
    }

    @Test
    void rejectsUnsupportedExtension() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> parser.parse("data.zip", new byte[] {1, 2, 3}));
        assertTrue(e.getMessage().contains("不支持的文件类型"));
    }

    @Test
    void roundTripsDocx() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("这是 Word 文档正文");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            bytes = out.toByteArray();
        }
        DocumentParser.ParsedDocument parsed = parser.parse("doc.docx", bytes);
        assertTrue(parsed.text().contains("这是 Word 文档正文"));
        assertEquals("DOCX", parsed.source());
    }

    @Test
    void roundTripsPdf() throws Exception {
        byte[] bytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("PDF content for extraction");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            bytes = out.toByteArray();
        }
        DocumentParser.ParsedDocument parsed = parser.parse("doc.pdf", bytes);
        assertTrue(parsed.text().contains("PDF content"));
        assertEquals("PDF", parsed.source());
    }
}
