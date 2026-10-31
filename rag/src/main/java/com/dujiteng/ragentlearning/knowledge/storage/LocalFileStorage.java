package com.dujiteng.ragentlearning.knowledge.storage;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class LocalFileStorage implements FileStorage {
    private final Path root;

    public LocalFileStorage(@Value("${knowledge.storage-root:./data/knowledge}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(long userId, long knowledgeBaseId, MultipartFile file, String extension) {
        // 原文件名只作为元数据；实际磁盘路径由后端生成。
        String key = userId + "/" + knowledgeBaseId + "/" + UUID.randomUUID() + "." + extension;
        Path destination = resolve(key);
        try {
            Files.createDirectories(destination.getParent());
            try (var input = file.getInputStream()) {
                long size = Files.copy(input, destination);
                return new StoredFile(key, size);
            }
        } catch (IOException error) {
            try { Files.deleteIfExists(destination); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw new ChatApiException(503, "FILE_STORAGE_UNAVAILABLE", "保存文件失败，请检查存储目录", error);
        }
    }

    @Override
    public Resource load(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new ChatApiException(404, "DOCUMENT_FILE_NOT_FOUND", "原文件不存在或暂时无法读取");
        }
        return new FileSystemResource(file);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException error) {
            throw new ChatApiException(503, "FILE_STORAGE_UNAVAILABLE", "清理未入库文件失败，请检查存储目录", error);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new ChatApiException(400, "INVALID_STORAGE_KEY", "文件存储编号无效");
        }
        return path;
    }
}
