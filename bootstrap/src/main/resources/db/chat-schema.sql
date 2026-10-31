-- 可在 D5 数据库上反复启动执行：保留已有表和已有消息。
CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_conversation (
    conversation_id VARCHAR(128) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- D5 已有表需要 ALTER 才能增加列；旧记录保留 NULL，等待明确绑定归属。
ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES app_user(id);
CREATE INDEX IF NOT EXISTS idx_chat_conversation_user ON chat_conversation (user_id, created_at);

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL REFERENCES chat_conversation(conversation_id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_message_conversation_id ON chat_message (conversation_id, id);

-- D8：知识库归属于用户；文档通过知识库关联到用户。
CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_knowledge_base_user ON knowledge_base (user_id, id);

CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_base(id),
    original_filename VARCHAR(255) NOT NULL,
    file_type VARCHAR(16) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_base ON knowledge_document (knowledge_base_id, id);

-- D9：对已有 D8 表进行增量迁移，保留原文件和文档记录。
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS parsed_text TEXT;
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS error_message VARCHAR(500);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS parsed_at TIMESTAMPTZ;
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS parse_started_at TIMESTAMPTZ;
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS parse_token VARCHAR(36);
