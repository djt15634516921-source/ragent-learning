package com.dujiteng.ragentlearning.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dujiteng.ragentlearning.knowledge.entity.DocumentIngestionTaskDO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocumentIngestionMapper extends BaseMapper<DocumentIngestionTaskDO> {
    String OWNED = " FROM document_ingestion_task t JOIN knowledge_document d ON d.id=t.document_id "
            + "JOIN knowledge_base k ON k.id=d.knowledge_base_id WHERE t.user_id=#{userId} "
            + "AND k.user_id=#{userId} AND k.id=#{baseId} AND k.deleted_at IS NULL ";

    @Select("SELECT * FROM document_ingestion_task WHERE document_id=#{documentId} AND status IN ('PENDING','RUNNING') LIMIT 1")
    DocumentIngestionTaskDO active(@Param("documentId") long documentId);

    @Select("SELECT t.* " + OWNED + "AND t.id=#{id}")
    DocumentIngestionTaskDO owned(@Param("userId") long userId, @Param("baseId") long baseId, @Param("id") String id);

    @Select("SELECT * FROM (SELECT DISTINCT ON (t.document_id) t.* " + OWNED
            + "ORDER BY t.document_id,t.created_at DESC,t.id DESC) latest ORDER BY created_at DESC LIMIT 100")
    List<DocumentIngestionTaskDO> latest(@Param("userId") long userId, @Param("baseId") long baseId);

    @Select("SELECT count(*) FROM document_ingestion_task WHERE status IN ('PENDING','RUNNING')")
    int activeCount();

    @Insert("""
        INSERT INTO document_ingestion_task(id,document_id,user_id,knowledge_base_id,status,stage,chunk_size,overlap,run_token,lease_until)
        VALUES(#{id},#{documentId},#{userId},#{baseId},#{status},'PENDING',#{chunkSize},#{overlap},#{token},
            CASE WHEN CAST(#{token} AS varchar) IS NOT NULL THEN CURRENT_TIMESTAMP+(#{leaseMs}*INTERVAL '1 millisecond') ELSE NULL END)
        """)
    int create(@Param("id") String id, @Param("documentId") long documentId, @Param("userId") long userId,
               @Param("baseId") long baseId, @Param("status") String status, @Param("chunkSize") int chunkSize,
               @Param("overlap") int overlap, @Param("token") String token, @Param("leaseMs") long leaseMs);

    @Select("SELECT id FROM document_ingestion_task WHERE status='PENDING' ORDER BY created_at,id LIMIT 20")
    List<String> pending();

    @Update("""
        UPDATE document_ingestion_task SET status='RUNNING',run_token=#{token},
            lease_until=CURRENT_TIMESTAMP+(#{leaseMs}*INTERVAL '1 millisecond'),updated_at=CURRENT_TIMESTAMP
        WHERE id=#{id} AND status='PENDING'
        """)
    int claim(@Param("id") String id, @Param("token") String token, @Param("leaseMs") long leaseMs);

    @Update("""
        UPDATE document_ingestion_task SET lease_until=CURRENT_TIMESTAMP+(#{leaseMs}*INTERVAL '1 millisecond')
        WHERE id=#{id} AND run_token=#{token} AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP
        """)
    int heartbeat(@Param("id") String id, @Param("token") String token, @Param("leaseMs") long leaseMs);

    @Update("""
        UPDATE document_ingestion_task SET stage=#{stage},updated_at=CURRENT_TIMESTAMP
        WHERE id=#{id} AND run_token=#{token} AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP
        """)
    int stage(@Param("id") String id, @Param("token") String token, @Param("stage") String stage);

    @Update("""
        UPDATE document_ingestion_task SET status='SUCCESS',stage='COMPLETED',run_token=NULL,lease_until=NULL,
            error_code=NULL,error_message=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE id=#{id} AND run_token=#{token} AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP
        """)
    int complete(@Param("id") String id, @Param("token") String token);

    @Update("""
        UPDATE document_ingestion_task SET status='FAILED',run_token=NULL,lease_until=NULL,
            error_code=#{code},error_message=#{message},updated_at=CURRENT_TIMESTAMP
        WHERE id=#{id} AND run_token=#{token} AND status='RUNNING'
        """)
    int fail(@Param("id") String id, @Param("token") String token, @Param("code") String code, @Param("message") String message);

    @Update("""
        UPDATE document_ingestion_task SET status='PENDING',run_token=NULL,lease_until=NULL,error_code=NULL,error_message=NULL,
            attempt=attempt+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='FAILED'
        """)
    int retry(@Param("id") String id);

    // 到期先使文档阶段令牌失效，再结束任务，防止旧进程迟到发布。
    @Update("""
        UPDATE knowledge_document d SET status='FAILED',parse_token=NULL,parse_started_at=NULL,
            error_message='入库执行已中断，请重试'
        WHERE d.status='RUNNING' AND EXISTS(SELECT 1 FROM document_ingestion_task t
            WHERE t.document_id=d.id AND t.status='RUNNING' AND t.stage='PARSING' AND t.lease_until<=CURRENT_TIMESTAMP)
        """)
    int fenceExpiredParsing();

    @Update("""
        UPDATE knowledge_document d SET index_status='FAILED',index_token=NULL,index_started_at=NULL,
            index_error='入库执行已中断，请重试'
        WHERE d.index_status='RUNNING' AND EXISTS(SELECT 1 FROM document_ingestion_task t
            WHERE t.document_id=d.id AND t.status='RUNNING' AND t.stage='INDEXING' AND t.lease_until<=CURRENT_TIMESTAMP)
        """)
    int fenceExpiredIndexing();

    @Update("""
        UPDATE document_ingestion_task SET status='FAILED',run_token=NULL,lease_until=NULL,
            error_code='INGESTION_INTERRUPTED',error_message='任务执行已中断，请手动重试；已完成阶段会校验后复用',updated_at=CURRENT_TIMESTAMP
        WHERE status='RUNNING' AND lease_until<=CURRENT_TIMESTAMP
        """)
    int failExpired();
}
