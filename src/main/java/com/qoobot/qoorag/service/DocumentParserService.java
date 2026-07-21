package com.qoobot.qoorag.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 文档解析服务：支持 TXT（UTF-8）和 PDF（PDFBox），统一返回纯文本
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    /** 解析文件，返回纯文本内容 */
    public String parse(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("文件名为空");
        }

        String lowerName = originalFilename.toLowerCase();
        if (lowerName.endsWith(".txt")) {
            return parseTxt(file);
        } else if (lowerName.endsWith(".pdf")) {
            return parsePdf(file);
        } else {
            throw new IllegalArgumentException("不支持的文件格式: " + originalFilename + "（仅支持 .txt / .pdf）");
        }
    }

    private String parseTxt(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        log.info("TXT 解析完成：{}，{} 字符", file.getOriginalFilename(), content.length());
        return content;
    }

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
                return text;
            } finally {
                document.close();
            }
        } finally {
            tempFile.delete();
        }
    }
}
