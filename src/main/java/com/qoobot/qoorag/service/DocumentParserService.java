package com.qoobot.qoorag.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 文档解析服务（RAG-025 扩充格式覆盖）。
 * <p>
 * 支持 TXT / MD / PDF / DOCX(.docx) / HTML(.html,.htm)。
 * DOCX 与 HTML 统一转换为「带 Markdown 语义的纯文本」：
 * 标题 → {@code #} 层级、表格 → Markdown 表格、列表/代码块保留原结构，
 * 从而直接复用 {@code IngestService} 的 structure 策略（RAG-021）做结构感知切块与表格识别，
 * 不必为每种格式单独写切块逻辑。
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    /** 解析文件，返回（可喂给结构切块器的）纯文本内容 */
    public String parse(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("文件名为空");
        }

        String lowerName = originalFilename.toLowerCase();
        if (lowerName.endsWith(".txt") || lowerName.endsWith(".md")) {
            return parseText(file);
        } else if (lowerName.endsWith(".pdf")) {
            return parsePdf(file);
        } else if (lowerName.endsWith(".docx")) {
            return parseDocx(file);
        } else if (lowerName.endsWith(".html") || lowerName.endsWith(".htm")) {
            return parseHtml(file);
        } else {
            throw new IllegalArgumentException(
                    "不支持的文件格式: " + originalFilename + "（仅支持 .txt / .md / .pdf / .docx / .html / .htm）");
        }
    }

    private String parseText(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String lowerName = file.getOriginalFilename().toLowerCase();
        String ext = lowerName.endsWith(".md") ? "MD" : "TXT";
        log.info("{} 解析完成：{}，{} 字符", ext, file.getOriginalFilename(), content.length());
        return content;
    }

    // ===== DOCX（Apache POI） =====

    private String parseDocx(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(file.getBytes()))) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    Integer level = headingLevel(paragraph);
                    if (level != null) {
                        sb.append("#".repeat(level)).append(' ').append(text).append("\n\n");
                    } else {
                        sb.append(text).append("\n\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    sb.append(tableToMarkdown(table)).append("\n\n");
                }
            }
            String out = sb.toString().trim();
            log.info("DOCX 解析完成：{}，{} 字符", file.getOriginalFilename(), out.length());
            return out;
        }
    }

    /** 依据段落样式名识别标题层级（Heading1..Heading6 / 含 "heading" 字样） */
    private static Integer headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            // 退而求其次：从 styleID 推断
            style = paragraph.getStyleID();
        }
        if (style != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)heading\\s*(\\d)|(\\d)").matcher(style);
            if (m.find()) {
                int lvl = m.group(1) != null ? Integer.parseInt(m.group(1))
                        : Integer.parseInt(m.group(2));
                if (lvl >= 1 && lvl <= 6) {
                    return lvl;
                }
            }
        }
        return null;
    }

    private static String tableToMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> header = cellTexts(rows.get(0));
        int cols = Math.max(header.size(), 1);
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("| ").append("--- | ".repeat(cols)).append("\n");
        for (int i = 1; i < rows.size(); i++) {
            sb.append("| ").append(String.join(" | ", cellTexts(rows.get(i)))).append(" |\n");
        }
        return sb.toString().trim();
    }

    private static List<String> cellTexts(XWPFTableRow row) {
        List<String> cells = new java.util.ArrayList<>();
        for (XWPFTableCell cell : row.getTableCells()) {
            cells.add(cell.getText().replace("\n", " ").trim());
        }
        return cells;
    }

    // ===== HTML（Jsoup） =====

    private String parseHtml(MultipartFile file) throws IOException {
        String html = new String(file.getBytes(), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(html);
        doc.select("script,style,noscript,template").remove();
        StringBuilder sb = new StringBuilder();
        Element body = doc.body();
        if (body != null) {
            for (Element child : body.children()) {
                convertElement(child, sb, 0);
            }
        }
        String out = sb.toString().trim();
        log.info("HTML 解析完成：{}，{} 字符", file.getOriginalFilename(), out.length());
        return out;
    }

    /** 将 HTML 块级元素递归转成 Markdown 语义文本（供 structure 切块器消费） */
    private void convertElement(Element e, StringBuilder sb, int depth) {
        String tag = e.tagName().toLowerCase();
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int lvl = Integer.parseInt(tag.substring(1));
                sb.append("#".repeat(lvl)).append(' ').append(e.text()).append("\n\n");
            }
            case "table" -> {
                sb.append(htmlTableToMarkdown(e)).append("\n\n");
            }
            case "pre" -> {
                sb.append("```\n").append(e.text()).append("\n```\n\n");
            }
            case "ul", "ol" -> {
                for (Element li : e.select("li")) {
                    sb.append("- ").append(li.text()).append("\n");
                }
                sb.append("\n");
            }
            case "blockquote" -> {
                for (String line : e.text().split("\n")) {
                    sb.append("> ").append(line).append("\n");
                }
                sb.append("\n");
            }
            case "p", "div", "section", "article", "header", "footer", "main", "aside", "br" -> {
                String text = e.text();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n\n");
                } else {
                    for (Element c : e.children()) {
                        convertElement(c, sb, depth + 1);
                    }
                }
            }
            default -> {
                String text = e.ownText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n\n");
                }
                for (Element c : e.children()) {
                    convertElement(c, sb, depth + 1);
                }
            }
        }
    }

    private static String htmlTableToMarkdown(Element table) {
        Elements headerCols = table.select("tr").first() == null
                ? new Elements() : table.select("tr").first().select("th,td");
        int cols = Math.max(headerCols.size(), 1);
        StringBuilder sb = new StringBuilder();
        sb.append("| ");
        for (Element c : headerCols) {
            sb.append(c.text().replace("\n", " ")).append(" | ");
        }
        sb.append("\n| ").append("--- | ".repeat(cols)).append("\n");
        for (Element tr : table.select("tr")) {
            Elements cells = tr.select("th,td");
            if (tr.select("th").isEmpty() == false && tr.equals(table.select("tr").first())) {
                continue; // 表头已处理
            }
            sb.append("| ");
            for (Element c : cells) {
                sb.append(c.text().replace("\n", " ")).append(" | ");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ===== PDF（PDFBox） =====

    private String parsePdf(MultipartFile file) throws IOException {
        // PDFBox 需要 File 或 InputStream，用临时文件避免内存问题
        File tempFile = File.createTempFile("qoorag_", ".pdf");
        try {
            file.transferTo(tempFile);
            PDDocument document;
            try {
                document = PDDocument.load(tempFile);
            } catch (InvalidPasswordException e) {
                throw new IllegalArgumentException(
                        "PDF 已加密，需要密码才能解析。请先解除密码保护（或用无密码版本）后再上传。");
            }
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                String text = stripper.getText(document);
                int pages = document.getNumberOfPages();
                if (text == null || text.isBlank()) {
                    if (pages <= 0) {
                        throw new IllegalArgumentException("PDF 文件无页面内容，无法解析。");
                    }
                    // 有页面但提取不到文本：典型为扫描件/图片型 PDF（无文本层）
                    throw new IllegalArgumentException(
                            "PDF 解析后未提取到文本（共 " + pages + " 页）。该文件很可能是扫描件/图片型 PDF，"
                                    + "未内嵌文本层，当前版本不支持 OCR 识别。请上传由文字生成的 PDF，"
                                    + "或将扫描件转为可检索的文本 PDF 后再入库。");
                }
                log.info("PDF 解析完成：{}，{} 页，{} 字符",
                        file.getOriginalFilename(), pages, text.length());
                // RAG-026：启发式识别 PDF 中「多列对齐」的文本行簇并转 Markdown 表格，
                // 供 structure 切块器识别为 TYPE_TABLE 块（表格类 query 可召回）。
                String withTables = detectPdfTables(text);
                return withTables;
            } finally {
                document.close();
            }
        } finally {
            tempFile.delete();
        }
    }

    // ===== RAG-026：PDF 表格启发式识别 =====

    /**
     * 把 PDFBox 提取文本中「连续多行、同列数、由 ≥2 空格分隔」的表格区块转成 Markdown 表格，
     * 供 structure 切块器识别为 TYPE_TABLE 块（表格类 query 可召回）。
     * 纯启发式：依赖 PDFBox 在列间插入的空格（sortByPosition=true 时更可靠）。
     * 局限：列间距极小或扫描版 PDF 无法识别，生产建议配合 tabula/pdfplumber，详见任务说明。
     */
    static String detectPdfTables(String text) {
        String[] rawLines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        List<String[]> tableRows = new ArrayList<>();
        for (String raw : rawLines) {
            String line = raw.replace("\t", "    ");
            if (isTableRow(line)) {
                tableRows.add(splitColumns(line));
            } else {
                if (!tableRows.isEmpty()) {
                    flushTable(out, tableRows);
                    tableRows.clear();
                }
                out.add(raw);
            }
        }
        if (!tableRows.isEmpty()) {
            flushTable(out, tableRows);
        }
        return String.join("\n", out);
    }

    private static void flushTable(List<String> out, List<String[]> tableRows) {
        if (tableRows.size() >= 2) {
            out.add(toMarkdownTable(tableRows));
        } else {
            for (String[] r : tableRows) {
                out.add(String.join(" ", r));
            }
        }
    }

    /** 是否为表格行：被 ≥2 空格切分为 ≥2 列，且非普通句尾、含数字/冒号或 ≥3 列等表格特征 */
    private static boolean isTableRow(String line) {
        if (line == null || line.length() < 6) {
            return false;
        }
        String[] cols = splitColumns(line);
        if (cols.length < 2) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.endsWith("。") || trimmed.endsWith("．") || trimmed.endsWith("?")
                || trimmed.endsWith("？") || trimmed.endsWith(".")) {
            return false;
        }
        boolean hasGap = line.contains("  ");
        boolean looksTabular = hasGap && (line.matches(".*\\d.*") || line.contains("：")
                || line.contains(":") || cols.length >= 3);
        return looksTabular;
    }

    private static String[] splitColumns(String line) {
        return Arrays.stream(line.trim().split("\\s{2,}"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    private static String toMarkdownTable(List<String[]> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        int cols = rows.get(0).length;
        long consistent = rows.stream().filter(r -> r.length == cols).count();
        // 列数不一致过多则退化为逐行管道表达（不生成错误表头）
        if (consistent < Math.max(2, rows.size() / 2)) {
            StringBuilder sb = new StringBuilder();
            for (String[] r : rows) {
                sb.append("| ").append(String.join(" | ", r)).append(" |\n");
            }
            return sb.toString().trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", rows.get(0))).append(" |\n");
        sb.append("| ").append("--- | ".repeat(cols)).append("\n");
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).length == cols) {
                sb.append("| ").append(String.join(" | ", rows.get(i))).append(" |\n");
            }
        }
        return sb.toString().trim();
    }
}
