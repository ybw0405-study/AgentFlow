package com.agentflow.workflow.config;

import com.agentflow.workflow.sse.SseHub;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Redis 订阅（SSE 扇出）+ SSE 任务线程池。 */
@Configuration
public class RedisEventsConfiguration {

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory factory,
                                                        SseHub sseHub) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        // SseHub 自身实现 MessageListener，注册即生效
        container.addMessageListener(sseHub, new ChannelTopic(SseHub.CHANNEL));
        return container;
    }

    @Bean(name = "sseTaskExecutor")
    public ThreadPoolTaskExecutor sseTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(32);
        executor.setThreadNamePrefix("sse-");
        executor.initialize();
        return executor;
    }
}
