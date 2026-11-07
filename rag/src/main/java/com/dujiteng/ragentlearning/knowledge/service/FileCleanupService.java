package com.dujiteng.ragentlearning.knowledge.service;

import java.util.List;

public interface FileCleanupService {
    int clean(List<Long> cleanupIds);
}
