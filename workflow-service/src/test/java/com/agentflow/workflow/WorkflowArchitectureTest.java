package com.agentflow.workflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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

    /**
     * Spring 4.3+ 对「单个构造函数」的 Bean 会自动注入，无需标注；但一旦出现多个构造函数，
     * 容器会退回寻找无参构造——找不到就抛 NoSuchMethodException，整个上下文启动失败。
     * 单测若都是手动 new，这类错误不会在测试期暴露，只在运行时炸。用静态规则把注入点
     * 变成硬约束：多构造函数的 Spring Bean 必须显式 @Autowired，或保留无参构造。
     */
    private static final ArchCondition<JavaClass> HAVE_DECLARED_INJECTION_POINT =
            new ArchCondition<JavaClass>(
                    "多构造函数的 Spring Bean 必须标注 @Autowired 或保留无参构造") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    var ctors = javaClass.getConstructors();
                    if (ctors.size() <= 1) {
                        return; // 单构造函数：Spring 自动注入，安全
                    }
                    boolean hasAutowired = ctors.stream()
                            .anyMatch(c -> c.isAnnotatedWith(Autowired.class)
                                    || c.isMetaAnnotatedWith(Autowired.class));
                    boolean hasNoArg = ctors.stream()
                            .anyMatch(c -> c.getParameters().isEmpty());
                    if (!hasAutowired && !hasNoArg) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                javaClass.getName() + " 声明了 " + ctors.size()
                                        + " 个构造函数，却既无 @Autowired 标注也无无参构造；"
                                        + "Spring 容器会尝试无参构造并在启动时失败"));
                    }
                }
            };

    @ArchTest
    static final ArchRule springBeansDeclareInjectionPoint = classes()
            .that().areMetaAnnotatedWith(Component.class)
            .should(HAVE_DECLARED_INJECTION_POINT);

    /** 包级无循环。 */
    @ArchTest
    static final ArchRule noPackageCycles = slices()
            .matching("com.agentflow.workflow.(*)..").should().beFreeOfCycles();
}
