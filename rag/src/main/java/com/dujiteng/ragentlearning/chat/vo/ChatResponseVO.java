package com.dujiteng.ragentlearning.chat.vo;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;
public record ChatResponseVO(String answer, List<RetrievedSourceVO> sources, Long knowledgeBaseId, QueryRewriteVO retrievalQuery) { }
