package com.dujiteng.ragentlearning.knowledge.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/** 业务层只保存存储编号；本地文件与对象存储可以提供不同实现。 */
public interface FileStorage {
    StoredFile store(long userId, long knowledgeBaseId, MultipartFile file, String extension);
    Resource load(String key);
    void delete(String key);

    record StoredFile(String key, long size) { }
}
