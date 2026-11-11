-- Agent 会话、可回放消息和 AgentScope 状态分开保存。
CREATE TABLE IF NOT EXISTS t_agent_conversation (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_base(id),
    title VARCHAR(128),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_conversation_owner_updated
    ON t_agent_conversation(user_id, updated_at DESC, id);

CREATE TABLE IF NOT EXISTS t_agent_message (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    result_status VARCHAR(24),
    tool_calls JSONB NOT NULL DEFAULT '[]'::jsonb,
    sources JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (conversation_id, user_id)
        REFERENCES t_agent_conversation(id, user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_agent_message_session_order
    ON t_agent_message(conversation_id, user_id, id DESC);

CREATE TABLE IF NOT EXISTS t_agent_state (
    user_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    state_key VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, session_id, state_key),
    FOREIGN KEY (session_id, user_id)
        REFERENCES t_agent_conversation(id, user_id) ON DELETE CASCADE
);
