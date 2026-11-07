package com.dujiteng.ragentlearning.chat.vo;

import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;

public record ChatHistoryItemVO(long id, String role, String content, List<RetrievedSourceVO> sources) { }
