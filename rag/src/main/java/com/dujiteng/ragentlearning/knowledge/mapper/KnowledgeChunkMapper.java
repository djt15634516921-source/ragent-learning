package com.dujiteng.ragentlearning.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeChunkDO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {
    @Insert("""
        <script>
        INSERT INTO knowledge_chunk (document_id, source_version, chunk_index, content, character_count, start_offset, end_offset)
        VALUES
        <foreach collection="rows" item="row" separator=",">
            (#{row.documentId}, #{row.sourceVersion}, #{row.chunkIndex}, #{row.content},
             #{row.characterCount}, #{row.startOffset}, #{row.endOffset})
        </foreach>
        </script>
        """)
    int insertBatch(@Param("rows") List<KnowledgeChunkDO> rows);

    @Select("""
        SELECT c.* FROM knowledge_chunk c
        JOIN knowledge_document d ON d.id = c.document_id
        JOIN knowledge_base k ON k.id = d.knowledge_base_id
        WHERE d.id = #{documentId} AND k.id = #{knowledgeBaseId} AND k.user_id = #{userId}
          AND d.status = 'SUCCESS' AND c.source_version = d.parse_version
          AND d.chunk_version = d.parse_version
        ORDER BY c.chunk_index
        """)
    IPage<KnowledgeChunkDO> selectOwnedPage(Page<KnowledgeChunkDO> page, @Param("userId") long userId,
            @Param("knowledgeBaseId") long knowledgeBaseId, @Param("documentId") long documentId);
}
