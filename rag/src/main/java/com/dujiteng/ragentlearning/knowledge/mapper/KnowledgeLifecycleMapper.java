package com.dujiteng.ragentlearning.knowledge.mapper;

import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeBaseDO;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeDocumentDO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeLifecycleMapper {
    @Select("SELECT * FROM knowledge_base WHERE id=#{baseId} AND user_id=#{userId} AND deleted_at IS NULL FOR UPDATE")
    KnowledgeBaseDO lockBase(@Param("userId") long userId, @Param("baseId") long baseId);

    @Select("SELECT * FROM knowledge_document WHERE knowledge_base_id=#{baseId} ORDER BY id FOR UPDATE")
    List<KnowledgeDocumentDO> lockDocuments(@Param("baseId") long baseId);

    @Delete("DELETE FROM knowledge_document WHERE id=#{documentId} AND knowledge_base_id=#{baseId}")
    int deleteDocument(@Param("baseId") long baseId, @Param("documentId") long documentId);

    @Update("UPDATE knowledge_base SET deleted_at=CURRENT_TIMESTAMP WHERE id=#{baseId} AND deleted_at IS NULL")
    int deleteBase(@Param("baseId") long baseId);

    @Select(value = "INSERT INTO file_cleanup(storage_key) VALUES(#{key}) ON CONFLICT(storage_key) DO UPDATE SET storage_key=EXCLUDED.storage_key RETURNING id", affectData = true)
    long enqueueCleanup(@Param("key") String key);

    @Select("SELECT id FROM file_cleanup WHERE next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 50")
    List<Long> pendingCleanup();

    @Select("SELECT storage_key FROM file_cleanup WHERE id=#{id}")
    String cleanupKey(@Param("id") long id);

    @Delete("DELETE FROM file_cleanup WHERE id=#{id}")
    int finishCleanup(@Param("id") long id);

    @Update("UPDATE file_cleanup SET attempts=attempts+1, last_error=#{error}, next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '1 minute' WHERE id=#{id}")
    int failCleanup(@Param("id") long id, @Param("error") String error);

    @Select("<script>SELECT count(*) FROM file_cleanup WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int countCleanup(@Param("ids") List<Long> ids);
}
