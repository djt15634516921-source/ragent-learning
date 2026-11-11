package com.dujiteng.ragentlearning.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("t_agent_message")
public record AgentMessageDO(@TableId(type = IdType.AUTO) long id,
                             String conversationId, long userId, String role, String content, String resultStatus,
                             String toolCalls, String sources, OffsetDateTime createdAt) {
}
