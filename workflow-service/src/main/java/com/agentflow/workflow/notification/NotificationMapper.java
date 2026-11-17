package com.agentflow.workflow.notification;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 站内通知（V006）。id 由业务键派生，投影重放不会产生重复通知。 */
@Mapper
public interface NotificationMapper {

    @Insert("INSERT INTO notification (id, tenant_id, user_id, type, payload_json)"
            + " VALUES (#{id}, #{tenantId}, #{userId}, #{type}, #{payloadJson})"
            + " ON DUPLICATE KEY UPDATE payload_json = VALUES(payload_json)")
    int insert(NotificationRow row);

    @Select("<script>SELECT seq, id, tenant_id AS tenantId, user_id AS userId, type,"
            + " payload_json AS payloadJson, read_at AS readAt, created_at AS createdAt"
            + " FROM notification WHERE user_id = #{userId}"
            + "<if test='unreadOnly'> AND read_at IS NULL</if>"
            + " ORDER BY seq DESC LIMIT #{limit}</script>")
    List<NotificationRow> listForUser(@Param("userId") String userId,
                                      @Param("unreadOnly") boolean unreadOnly,
                                      @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM notification WHERE user_id = #{userId} AND read_at IS NULL")
    int countUnread(@Param("userId") String userId);

    @Update("UPDATE notification SET read_at = NOW(3)"
            + " WHERE id = #{id} AND user_id = #{userId} AND read_at IS NULL")
    int markRead(@Param("id") String id, @Param("userId") String userId);

    @Update("UPDATE notification SET read_at = NOW(3)"
            + " WHERE user_id = #{userId} AND read_at IS NULL")
    int markAllRead(@Param("userId") String userId);

    record NotificationRow(long seq, String id, String tenantId, String userId, String type,
                           String payloadJson, Timestamp readAt, Timestamp createdAt) {
    }
}
