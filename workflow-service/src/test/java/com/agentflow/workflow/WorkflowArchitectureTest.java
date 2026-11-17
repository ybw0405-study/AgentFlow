package com.agentflow.workflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** §13.1 架构门禁（workflow-service）。 */
@AnalyzeClasses(packages = "com.agentflow.workflow")
class WorkflowArchitectureTest {

    /** 对引擎的 HTTP 调用只能经 EngineGatewayClient（D1 唯一出口）。 */
    @ArchTest
    static final ArchRule engineAccessOnlyViaGatewayClient = noClasses()
            .that().resideOutsideOfPackage("com.agentflow.workflow.engine..")
            .should().dependOnClassesThat()
            .resideInAPackage("org.springframework.web.client..");

    /** Mapper 接口不得依赖 web/service 层。 */
    @ArchTest
    static final ArchRule mappersStayInDataLayer = noClasses()
            .that().areAnnotatedWith(org.apache.ibatis.annotations.Mapper.class)
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "com.agentflow.workflow.config..");

    /** 包级无循环。 */
    @ArchTest
    static final ArchRule noPackageCycles = slices()
            .matching("com.agentflow.workflow.(*)..").should().beFreeOfCycles();
}
