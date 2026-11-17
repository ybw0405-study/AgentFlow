package com.agentflow.workflow.execution;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProjectorCursorMapper {

    @Select("SELECT last_seq FROM projector_cursor WHERE projector = 'engine-events'")
    Long findLastSeq();

    /** 首次初始化 + 更新；事务内调用。 */
    @Insert("INSERT INTO projector_cursor (projector, last_seq) VALUES ('engine-events', #{seq})"
            + " ON DUPLICATE KEY UPDATE last_seq = GREATEST(last_seq, VALUES(last_seq))")
    int upsert(@Param("seq") long seq);
}
