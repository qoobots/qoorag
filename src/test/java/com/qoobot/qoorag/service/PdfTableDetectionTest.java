package com.qoobot.qoorag.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RAG-026：PDF 表格启发式识别单测（纯逻辑，规避 JDK25/Mockito 预存问题） */
public class PdfTableDetectionTest {

    @Test
    void detectsTwoColumnTableWithHeaderAndBody() {
        String text = "型号    算力    发布年\nQ1      128Q    2025\nQ2      256Q    2026";
        String out = DocumentParserService.detectPdfTables(text);
        assertTrue(out.contains("| 型号 | 算力 | 发布年 |"), "应生成 Markdown 表头");
        assertTrue(out.contains("| --- | --- | --- |"), "应生成分隔行");
        assertTrue(out.contains("| Q1 | 128Q | 2025 |"), "应生成表体");
    }

    @Test
    void ignoresOrdinarySentence() {
        String text = "量子科技在 2025 年发布了新型量子芯片，性能大幅提升。\n另一段描述性文字。";
        String out = DocumentParserService.detectPdfTables(text);
        assertFalse(out.contains("|"), "普通段落不应被识别为表格");
    }

    @Test
    void singleTableRowFallsBackToPlainText() {
        String text = "仅有一行    带两列";
        String out = DocumentParserService.detectPdfTables(text);
        assertFalse(out.contains("\n| --- |"), "单行不应生成表格分隔行");
    }
}
