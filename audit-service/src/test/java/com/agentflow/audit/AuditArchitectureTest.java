package com.agentflow.audit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** §13.1 架构门禁（audit-service）。 */
@AnalyzeClasses(packages = "com.agentflow.audit")
class AuditArchitectureTest {

    /** 审计只追加：repo 包禁止依赖 MyBatis 的 Update/Delete 注解（§13.3）。 */
    @ArchTest
    static final ArchRule auditIsAppendOnly = noClasses()
            .that().resideInAPackage("com.agentflow.audit.repo..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "org.apache.ibatis.annotations.Update")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "org.apache.ibatis.annotations.Delete");

    @ArchTest
    static final ArchRule noPackageCycles = slices()
            .matching("com.agentflow.audit.(*)..").should().beFreeOfCycles();
}
