package com.qoobot.qoorag.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** RAG-025：DOCX / HTML 解析单元测试（纯逻辑，不依赖 Mockito，规避 JDK25 预存问题） */
public class DocumentParserServiceTest {

    private final DocumentParserService parser = new DocumentParserService();

    @Test
    void parseDocx_convertsHeadingsAndTableToMarkdown() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph h = doc.createParagraph();
            h.setStyle("Heading1");
            h.createRun().setText("产品概述");

            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("量子科技专注于量子计算硬件。");

            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("型号");
            table.getRow(0).getCell(1).setText("算力");
            table.getRow(1).getCell(0).setText("Q1");
            table.getRow(1).getCell(1).setText("128Q");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            MultipartFile file = new MockMultipartFile("f.docx", "spec.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    baos.toByteArray());

            String text = parser.parse(file);
            assertTrue(text.contains("# 产品概述"), "应识别一级标题");
            assertTrue(text.contains("量子科技专注于量子计算硬件"), "应保留正文");
            assertTrue(text.contains("| 型号 | 算力 |"), "应转 Markdown 表头");
            assertTrue(text.contains("| Q1 | 128Q |"), "应转 Markdown 表体");
        }
    }

    @Test
    void parseHtml_convertsHeadingsTableListToMarkdown() throws Exception {
        String html = "<html><body>"
                + "<h2>客户案例</h2>"
                + "<p>某银行采用本平台构建合规知识库。</p>"
                + "<ul><li>场景A</li><li>场景B</li></ul>"
                + "<table><tr><th>指标</th><th>值</th></tr>"
                + "<tr><td>召回率</td><td>95%</td></tr></table>"
                + "</body></html>";
        MultipartFile file = new MockMultipartFile("f.html", "case.html",
                "text/html", html.getBytes("UTF-8"));

        String text = parser.parse(file);
        assertTrue(text.contains("## 客户案例"), "应识别二级标题");
        assertTrue(text.contains("某银行采用本平台"), "应保留正文");
        assertTrue(text.contains("- 场景A") && text.contains("- 场景B"), "应转无序列表");
        assertTrue(text.contains("| 指标 | 值 |") && text.contains("| 召回率 | 95% |"), "应转 Markdown 表格");
    }

    @Test
    void parse_rejectsUnsupportedFormat() {
        MultipartFile file = new MockMultipartFile("f.xyz", "a.xyz", "text/plain", "x".getBytes());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(file));
    }
}
