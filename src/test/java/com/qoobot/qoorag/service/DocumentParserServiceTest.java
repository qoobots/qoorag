package com.qoobot.qoorag.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** DocumentParserService 单元测试：TXT / PDF 解析与不支持格式异常 */
public class DocumentParserServiceTest {

    private final DocumentParserService service = new DocumentParserService();

    @Test
    void parse_txt_utf8() throws Exception {
        MultipartFile file = new MockMultipartFile("file", "hello.txt", "text/plain",
                "中文测试内容".getBytes(StandardCharsets.UTF_8));
        String text = service.parse(file);
        assertEquals("中文测试内容", text);
    }

    @Test
    void parse_pdf_extracts_text() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Hello QooRAG PDF");
                cs.endText();
            }
            doc.save(baos);
        }
        MultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", baos.toByteArray());
        String text = service.parse(file);
        assertTrue(text.contains("Hello QooRAG PDF"));
    }

    @Test
    void parse_unsupported_format_throws() {
        MultipartFile file = new MockMultipartFile("file", "doc.doc", "application/octet-stream", "x".getBytes());
        assertThrows(IllegalArgumentException.class, () -> service.parse(file));
    }
}
