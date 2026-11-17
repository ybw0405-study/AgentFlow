package com.agentflow.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** §13.1 架构门禁（agent-gateway）。 */
@AnalyzeClasses(packages = "com.agentflow.gateway")
class GatewayArchitectureTest {

    /** 网关不得依赖数据库与 MQ（无状态边缘）。 */
    @ArchTest
    static final ArchRule noDataStores = noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("org.apache.ibatis..", "com.mysql..",
                    "org.apache.rocketmq..", "java.sql..");

    /** 网关不得依赖业务服务内部类。 */
    @ArchTest
    static final ArchRule noBusinessInternals = noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("com.agentflow.workflow..", "com.agentflow.audit..");
}
