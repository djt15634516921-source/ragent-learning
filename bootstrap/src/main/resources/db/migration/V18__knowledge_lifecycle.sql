-- 增量迁移，可重复执行；旧知识库、会话、消息及来源快照不变。
ALTER TABLE knowledge_base ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- 与删除元数据同事务写入，磁盘清理在提交后执行；进程退出后仍可重试。
CREATE TABLE IF NOT EXISTS file_cleanup (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS document_ingestion_task (
    id VARCHAR(36) PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_base(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    stage VARCHAR(16) NOT NULL CHECK (stage IN ('PENDING', 'PARSING', 'CHUNKING', 'INDEXING', 'COMPLETED')),
    chunk_size INTEGER NOT NULL CHECK (chunk_size BETWEEN 200 AND 4000),
    overlap INTEGER NOT NULL CHECK (overlap >= 0 AND overlap < chunk_size),
    attempt INTEGER NOT NULL DEFAULT 1,
    run_token VARCHAR(36),
    lease_until TIMESTAMPTZ,
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ingestion_active_document ON document_ingestion_task(document_id)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX IF NOT EXISTS idx_ingestion_dispatch ON document_ingestion_task(status, created_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_document ON document_ingestion_task(document_id, created_at DESC);
