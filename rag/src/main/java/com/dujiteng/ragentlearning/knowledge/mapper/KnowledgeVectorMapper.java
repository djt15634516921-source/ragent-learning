package com.dujiteng.ragentlearning.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeVectorDO;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeVectorMapper extends BaseMapper<KnowledgeVectorDO> {
    // 当前学习规模用精确余弦检索，先过滤归属/版本，再排序，不提前引入近似索引。
    String READY = " FROM knowledge_chunk_vector v JOIN knowledge_chunk c ON c.id=v.chunk_id "
            + "JOIN knowledge_document d ON d.id=c.document_id JOIN knowledge_base k ON k.id=d.knowledge_base_id "
            + "WHERE k.id=#{knowledgeBaseId} AND k.user_id=#{userId} AND k.deleted_at IS NULL AND d.status='SUCCESS' AND d.chunk_version=d.parse_version "
            + "AND c.source_version=d.parse_version AND v.source_version=d.parse_version "
            + "AND d.index_status='SUCCESS' AND d.indexed_revision=d.chunk_revision AND v.chunk_revision=d.chunk_revision "
            + "AND v.embedding_profile=#{profile} AND d.index_profile=#{profile}";
    @Select("SELECT count(*) " + READY)
    long countReady(@Param("userId") long userId, @Param("knowledgeBaseId") long knowledgeBaseId, @Param("profile") String profile);
    @Select("""
        SELECT c.id AS chunk_id, d.id AS document_id, d.original_filename AS filename, c.chunk_index,
               c.start_offset, c.end_offset, c.content, 1 - (v.embedding <=> CAST(#{vector} AS vector)) AS score,
               c.source_version, NULL::double precision AS rerank_score
        """ + READY + " AND 1 - (v.embedding <=> CAST(#{vector} AS vector)) >= #{minimum} "
            + "ORDER BY v.embedding <=> CAST(#{vector} AS vector), c.id LIMIT #{limit}")
    List<RetrievedSourceVO> search(@Param("userId") long userId, @Param("knowledgeBaseId") long knowledgeBaseId, @Param("profile") String profile,
            @Param("vector") String vector, @Param("minimum") double minimum, @Param("limit") int limit);
    @Delete("DELETE FROM knowledge_chunk_vector WHERE chunk_id IN (SELECT id FROM knowledge_chunk WHERE document_id=#{documentId})")
    int deleteDocument(@Param("documentId") long documentId);
    @Insert("""
        <script>
        INSERT INTO knowledge_chunk_vector (chunk_id, source_version, chunk_revision, embedding_profile, embedding_model, embedding)
        VALUES <foreach collection="rows" item="r" separator=",">
            (#{r.chunkId}, #{version}, #{revision}, #{profile}, #{model}, CAST(#{r.embedding} AS vector))
        </foreach>
        </script>
        """)
    int insertBatch(@Param("rows") List<KnowledgeVectorDO> rows, @Param("version") long version,
            @Param("revision") long revision, @Param("profile") String profile, @Param("model") String model);
    @Update("""
        UPDATE knowledge_document SET index_status='RUNNING', index_error=NULL, index_started_at=CURRENT_TIMESTAMP,
            index_token=#{token}, index_count=0, indexed_at=NULL
        WHERE id=#{documentId}
        """)
    int claim(@Param("documentId") long documentId, @Param("token") String token);
    @Update("""
        UPDATE knowledge_document SET index_status='SUCCESS', index_error=NULL, index_started_at=NULL, index_token=NULL,
            indexed_revision=#{revision}, index_model=#{model}, index_profile=#{profile}, index_count=#{count}, indexed_at=CURRENT_TIMESTAMP
        WHERE id=#{documentId} AND index_token=#{token} AND chunk_revision=#{revision}
        """)
    int complete(@Param("documentId") long documentId, @Param("token") String token, @Param("revision") long revision,
            @Param("model") String model, @Param("profile") String profile, @Param("count") int count);
    @Update("""
        UPDATE knowledge_document SET index_started_at=CURRENT_TIMESTAMP
        WHERE id=#{documentId} AND index_token=#{token} AND index_status='RUNNING' AND chunk_revision=#{revision}
        """)
    int renew(@Param("documentId") long documentId, @Param("token") String token, @Param("revision") long revision);
    @Update("""
        UPDATE knowledge_document SET index_status='FAILED', index_error=#{error}, index_token=NULL, index_started_at=NULL
        WHERE id=#{documentId} AND index_token=#{token} AND index_status='RUNNING'
        """)
    int fail(@Param("documentId") long documentId, @Param("token") String token, @Param("error") String error);
}
