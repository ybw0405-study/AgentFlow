package com.agentflow.workflow.knowledge;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 知识库 / 文档 / 切片的持久化（V009）。所有查询都带 tenant_id 下推，跨租户读不到行。 */
@Mapper
public interface KnowledgeMapper {

    String BASE_COLUMNS = "id, tenant_id AS tenantId, name, description,"
            + " created_by AS createdBy, created_at AS createdAt";

    /** 列表与单条查询共用同一组列：record 有 8 个字段，构造器映射要求列数一致。 */
    String BASE_SELECT = "SELECT " + BASE_COLUMNS + ","
            + " (SELECT COUNT(*) FROM knowledge_document d"
            + " WHERE d.kb_id = b.id AND d.tenant_id = b.tenant_id AND d.deleted_at IS NULL)"
            + " AS documentCount,"
            + " (SELECT COUNT(*) FROM knowledge_chunk c"
            + " WHERE c.kb_id = b.id AND c.tenant_id = b.tenant_id) AS chunkCount"
            + " FROM knowledge_base b";

    @Insert("INSERT INTO knowledge_base (id, tenant_id, name, description, created_by)"
            + " VALUES (#{id}, #{tenantId}, #{name}, #{description}, #{createdBy})")
    int insertBase(BaseRow row);

    @Select(BASE_SELECT + " WHERE b.tenant_id = #{tenantId} AND b.deleted_at IS NULL"
            + " ORDER BY b.created_at DESC")
    List<BaseRow> listBases(@Param("tenantId") String tenantId);

    @Select(BASE_SELECT + " WHERE b.id = #{id} AND b.tenant_id = #{tenantId}"
            + " AND b.deleted_at IS NULL")
    BaseRow findBase(@Param("id") String id, @Param("tenantId") String tenantId);

    @Update("UPDATE knowledge_base SET deleted_at = NOW(3)"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int softDeleteBase(@Param("id") String id, @Param("tenantId") String tenantId);

    @Insert("INSERT INTO knowledge_document (id, tenant_id, kb_id, title, source, char_count,"
            + " chunk_count, created_by)"
            + " VALUES (#{id}, #{tenantId}, #{kbId}, #{title}, #{source}, #{charCount},"
            + " #{chunkCount}, #{createdBy})")
    int insertDocument(DocumentRow row);

    @Select("SELECT id, tenant_id AS tenantId, kb_id AS kbId, title, source,"
            + " char_count AS charCount, chunk_count AS chunkCount,"
            + " created_by AS createdBy, created_at AS createdAt"
            + " FROM knowledge_document"
            + " WHERE kb_id = #{kbId} AND tenant_id = #{tenantId} AND deleted_at IS NULL"
            + " ORDER BY created_at DESC")
    List<DocumentRow> listDocuments(@Param("kbId") String kbId,
                                    @Param("tenantId") String tenantId);

    @Select("SELECT id, tenant_id AS tenantId, kb_id AS kbId, title, source,"
            + " char_count AS charCount, chunk_count AS chunkCount,"
            + " created_by AS createdBy, created_at AS createdAt"
            + " FROM knowledge_document"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    DocumentRow findDocument(@Param("id") String id, @Param("tenantId") String tenantId);

    @Update("UPDATE knowledge_document SET deleted_at = NOW(3)"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int softDeleteDocument(@Param("id") String id, @Param("tenantId") String tenantId);

    @Insert("INSERT INTO knowledge_chunk (id, tenant_id, kb_id, document_id, ordinal,"
            + " document_title, content, token_count)"
            + " VALUES (#{id}, #{tenantId}, #{kbId}, #{documentId}, #{ordinal},"
            + " #{documentTitle}, #{content}, #{tokenCount})")
    int insertChunk(ChunkRow row);

    /** 切片随文档软删硬删：检索 SQL 因此无需 JOIN 软删状态，过滤条件可完全下推。 */
    @Delete("DELETE FROM knowledge_chunk WHERE document_id = #{documentId}"
            + " AND tenant_id = #{tenantId}")
    int deleteChunksByDocument(@Param("documentId") String documentId,
                               @Param("tenantId") String tenantId);

    @Delete("DELETE FROM knowledge_chunk WHERE kb_id = #{kbId} AND tenant_id = #{tenantId}")
    int deleteChunksByBase(@Param("kbId") String kbId, @Param("tenantId") String tenantId);

    @Delete("DELETE FROM knowledge_document WHERE kb_id = #{kbId} AND tenant_id = #{tenantId}")
    int deleteDocumentsByBase(@Param("kbId") String kbId, @Param("tenantId") String tenantId);

    /**
     * 检索候选集：租户 + 知识库 id 列表在 SQL 层下推，LIMIT 限制单次扫描上限。
     * 相关性打分在应用层做 BM25，避免引入 MySQL 全文解析器（中文需 ngram 插件）。
     */
    @Select("<script>SELECT id, tenant_id AS tenantId, kb_id AS kbId,"
            + " document_id AS documentId, ordinal, document_title AS documentTitle,"
            + " content, token_count AS tokenCount FROM knowledge_chunk"
            + " WHERE tenant_id = #{tenantId} AND kb_id IN"
            + " <foreach item='kb' collection='kbIds' open='(' separator=',' close=')'>"
            + " #{kb}</foreach>"
            + " ORDER BY kb_id, document_id, ordinal LIMIT #{limit}</script>")
    List<ChunkRow> listChunksForSearch(@Param("tenantId") String tenantId,
                                       @Param("kbIds") List<String> kbIds,
                                       @Param("limit") int limit);

    record BaseRow(String id, String tenantId, String name, String description,
                   String createdBy, Timestamp createdAt, long documentCount, long chunkCount) {
    }

    record DocumentRow(String id, String tenantId, String kbId, String title, String source,
                       int charCount, int chunkCount, String createdBy, Timestamp createdAt) {
    }

    record ChunkRow(String id, String tenantId, String kbId, String documentId, int ordinal,
                    String documentTitle, String content, int tokenCount) {
    }
}
