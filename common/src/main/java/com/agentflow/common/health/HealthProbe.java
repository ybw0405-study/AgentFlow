package com.agentflow.common.health;

/** 依赖探针：实现方负责在超时内完成一次轻量检查，不抛异常，用结果表达状态。 */
@FunctionalInterface
public interface HealthProbe {

    DependencyHealth check();
}
