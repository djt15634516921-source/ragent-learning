package com.dujiteng.ragentlearning.chat.vo;

import java.util.List;

public record ChatHistoryPageVO(List<ChatHistoryItemVO> messages, Long nextBeforeId, boolean hasMore) { }
