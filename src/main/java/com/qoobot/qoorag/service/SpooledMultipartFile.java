package com.qoobot.qoorag.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 基于磁盘临时文件的 {@link MultipartFile} 实现（RAG-030）。
 * <p>
 * 异步入库时，HTTP 请求已结束、原始的 {@code MultipartFile} 随时可能被清理，
 * 因此入队时先把内容落盘，后台线程通过本适配器读取，保证任务可执行、可重试、可恢复。
 */
public class SpooledMultipartFile implements MultipartFile {

    private final Path path;
    private final String filename;

    public SpooledMultipartFile(Path path, String filename) {
        this.path = path;
        this.filename = filename;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return filename;
    }

    @Override
    public String getContentType() {
        return null; // 内容类型仅用于元数据记录，解析器按扩展名判断
    }

    @Override
    public boolean isEmpty() {
        try {
            return Files.size(path) == 0;
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    public long getSize() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(path);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(getBytes());
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        Files.copy(path, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** 底层文件路径（便于调试） */
    public Path path() {
        return path;
    }
}
