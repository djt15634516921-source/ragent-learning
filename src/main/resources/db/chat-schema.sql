-- 一条记录代表一个会话。
CREATE TABLE IF NOT EXISTS chat_conversation (
                                                 conversation_id VARCHAR(128) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- 一条记录代表一条消息。
CREATE TABLE IF NOT EXISTS chat_message (
                                            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                            conversation_id VARCHAR(128) NOT NULL
    REFERENCES chat_conversation(conversation_id),
    role VARCHAR(16) NOT NULL
    CHECK (role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- 方便按会话查找消息，并按消息编号排序。
CREATE INDEX IF NOT EXISTS idx_chat_message_conversation_id
    ON chat_message (conversation_id, id);
