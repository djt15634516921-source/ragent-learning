package com.dujiteng.ragentlearning.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("t_agent_conversation")
public record AgentConversationDO(@TableId(type = IdType.INPUT) String id,
                                  long userId, long knowledgeBaseId, String title, long revision,
                                  OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
