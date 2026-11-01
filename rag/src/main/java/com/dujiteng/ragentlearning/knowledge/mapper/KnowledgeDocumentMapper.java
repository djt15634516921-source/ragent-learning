package com.dujiteng.ragentlearning.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dujiteng.ragentlearning.knowledge.entity.KnowledgeDocumentDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentDO> {
    String META = "d.id, d.knowledge_base_id, d.original_filename, d.file_type, d.file_size, d.storage_key, d.status, d.created_at, d.error_message, d.parsed_at, d.parse_version, d.chunk_version, d.chunk_size, d.chunk_overlap, d.chunk_count, d.chunked_at, d.chunk_revision, d.index_status, d.index_error, d.index_started_at, d.index_token, d.indexed_revision, d.index_model, d.index_profile, d.index_count, d.indexed_at";
    @Select(value = """
        INSERT INTO knowledge_document (knowledge_base_id, original_filename, file_type, file_size, storage_key)
        SELECT id, #{filename}, #{fileType}, #{size}, #{storageKey} FROM knowledge_base
        WHERE id = #{knowledgeBaseId} AND user_id = #{userId} RETURNING *
        """, affectData = true)
    KnowledgeDocumentDO createOwned(@Param("userId") long userId, @Param("knowledgeBaseId") long knowledgeBaseId,
        @Param("filename") String filename, @Param("fileType") String fileType, @Param("size") long size, @Param("storageKey") String storageKey);
    @Select("SELECT " + META + " FROM knowledge_document d JOIN knowledge_base k ON k.id=d.knowledge_base_id WHERE k.id=#{knowledgeBaseId} AND k.user_id=#{userId} ORDER BY d.id DESC")
    List<KnowledgeDocumentDO> listOwned(@Param("userId") long userId, @Param("knowledgeBaseId") long knowledgeBaseId);
    @Select("SELECT " + META + " FROM knowledge_document d JOIN knowledge_base k ON k.id=d.knowledge_base_id WHERE d.id=#{documentId} AND k.id=#{knowledgeBaseId} AND k.user_id=#{userId}")
    KnowledgeDocumentDO findOwned(@Param("userId") long userId, @Param("knowledgeBaseId") long knowledgeBaseId, @Param("documentId") long documentId);
    @Select("SELECT d.* FROM knowledge_document d JOIN knowledge_base k ON k.id=d.knowledge_base_id WHERE d.id=#{documentId} AND k.id=#{knowledgeBaseId} AND k.user_id=#{userId}")
    KnowledgeDocumentDO textOwned(@Param("userId") long userId, @Param("knowledgeBaseId") long knowledgeBaseId, @Param("documentId") long documentId);
    @Update("""
                UPDATE knowledge_document d SET status = 'RUNNING', parsed_text = NULL,
                    error_message = NULL, parsed_at = NULL, parse_started_at = CURRENT_TIMESTAMP, parse_token = #{token},
                    chunk_version = NULL, chunk_size = NULL, chunk_overlap = NULL, chunk_count = 0, chunked_at = NULL, index_status = 'PENDING', index_error = NULL, index_token = NULL, index_started_at = NULL, indexed_revision = NULL, index_count = 0, indexed_at = NULL
                WHERE d.id = #{documentId} AND d.knowledge_base_id = #{knowledgeBaseId}
                  AND EXISTS (SELECT 1 FROM knowledge_base k WHERE k.id = d.knowledge_base_id AND k.user_id = #{userId})
                  AND (d.status <> 'RUNNING' OR d.parse_started_at IS NULL
                       OR d.parse_started_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes')
                """)
    int startParsing(@Param("userId") long userId, @Param("knowledgeBaseId") long knowledgeBaseId, @Param("documentId") long documentId, @Param("token") String token);
    @Update("""
                UPDATE knowledge_document d SET status = #{status}, parsed_text = #{text}, error_message = #{error},
                    parsed_at = CASE WHEN #{status} = 'SUCCESS' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    parse_started_at = NULL, parse_token = NULL,
                    parse_version = parse_version + CASE WHEN #{status} = 'SUCCESS' THEN 1 ELSE 0 END
                WHERE d.id = #{documentId} AND d.knowledge_base_id = #{knowledgeBaseId} AND d.status = 'RUNNING' AND d.parse_token = #{token}
                  AND EXISTS (SELECT 1 FROM knowledge_base k WHERE k.id = d.knowledge_base_id AND k.user_id = #{userId})
                """)
    int finishParsing(@Param("userId") long userId, @Param("knowledgeBaseId") long knowledgeBaseId, @Param("documentId") long documentId,
        @Param("token") String token, @Param("text") String text, @Param("error") String error, @Param("status") String status);

    @Select("""
        SELECT d.* FROM knowledge_document d JOIN knowledge_base k ON k.id = d.knowledge_base_id
        WHERE d.id = #{documentId} AND k.id = #{knowledgeBaseId} AND k.user_id = #{userId}
        FOR UPDATE OF d
        """)
    KnowledgeDocumentDO lockOwned(@Param("userId") long userId, @Param("knowledgeBaseId") long knowledgeBaseId,
                                  @Param("documentId") long documentId);

    @Update("""
        UPDATE knowledge_document d SET chunk_version = #{version}, chunk_size = #{chunkSize},
            chunk_overlap = #{overlap}, chunk_count = #{count}, chunked_at = CURRENT_TIMESTAMP,
            chunk_revision = chunk_revision + 1, index_status = 'PENDING', index_error = NULL, index_token = NULL, index_started_at = NULL, indexed_revision = NULL, index_count = 0, indexed_at = NULL
        WHERE d.id = #{documentId} AND d.knowledge_base_id = #{knowledgeBaseId}
          AND d.status = 'SUCCESS' AND d.parse_version = #{version}
          AND EXISTS (SELECT 1 FROM knowledge_base k WHERE k.id = d.knowledge_base_id AND k.user_id = #{userId})
        """)
    int markChunked(@Param("userId") long userId, @Param("knowledgeBaseId") long knowledgeBaseId,
                    @Param("documentId") long documentId, @Param("version") long version,
                    @Param("chunkSize") int chunkSize, @Param("overlap") int overlap, @Param("count") int count);
}
