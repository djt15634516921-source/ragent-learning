package com.dujiteng.ragentlearning.chat.vo;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;
public record ChatMessageVO(String role, String content, List<RetrievedSourceVO> sources) { }
