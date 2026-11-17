package com.agentflow.workflow.projector;

import com.agentflow.workflow.engine.EngineGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 事件投影器（D2）：轮询引擎全局事件尾读接口，把事件流投影到 MySQL 执行索引表。
 * 游标存 projector_cursor，与投影写入同事务；崩溃后从游标续读，事件幂等重放。
 */
@Component
public class EventProjector {

    private static final Logger log = LoggerFactory.getLogger(EventProjector.class);

    private final EngineGatewayClient engine;
    private final ProjectorWriter writer;
    private final int pageSize;

    public EventProjector(EngineGatewayClient engine, ProjectorWriter writer,
                          @Value("${agentflow.engine.poll-interval-ms:500}") int pollIntervalMs) {
        this.engine = engine;
        this.writer = writer;
        this.pageSize = 500;
    }

    @Scheduled(fixedDelayString = "${agentflow.engine.poll-interval-ms:500}",
            initialDelay = 3000)
    public void poll() {
        try {
            long cursor = writer.currentCursor();
            while (true) {
                EngineGatewayClient.EventsPage page;
                try {
                    page = engine.tailEvents(cursor, pageSize);
                } catch (Exception e) {
                    log.debug("engine tail unavailable: {}", e.getMessage());
                    return;  // 下一轮再试；游标不动
                }
                if (page == null || page.events() == null || page.events().isEmpty()) {
                    return;
                }
                long next = writer.writeBatch(page.events());
                if (next <= cursor) {
                    // 游标未前进（重复页/写入幂等命中）：立即退出，避免死循环空转
                    log.warn("projector cursor did not advance at seq={}, stop this round", cursor);
                    return;
                }
                cursor = next;
                if (page.events().size() < pageSize) {
                    return;
                }
            }
        } catch (Exception e) {
            log.error("projector poll failed", e);
        }
    }
}
