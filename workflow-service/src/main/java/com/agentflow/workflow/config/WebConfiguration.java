package com.agentflow.workflow.config;

import com.agentflow.common.web.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册 common 横切组件（TraceIdFilter 最高优先级；全局异常处理）。 */
@Configuration
public class WebConfiguration {

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
