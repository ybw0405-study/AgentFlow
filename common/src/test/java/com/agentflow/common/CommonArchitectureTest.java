package com.agentflow.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** common 只放跨切面原语：禁止基础设施依赖、禁止包循环（§13.1）。 */
@AnalyzeClasses(packages = "com.agentflow.common")
class CommonArchitectureTest {

    @ArchTest
    static final ArchRule noInfrastructure = noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("org.apache.rocketmq..", "com.mysql..", "java.sql..");

    @ArchTest
    static final ArchRule noCycles = slices()
            .matching("com.agentflow.common.(*)..").should().beFreeOfCycles();
}
