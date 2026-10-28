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
