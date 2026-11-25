package com.agentflow.connector;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * §13 架构门禁（connector-service）。
 *
 * <p>本模块是凭据加密与出网沙箱的敏感边界，此前缺少与其他四个模块一致的架构约束。
 * 规则聚焦三条真实红线，而非空泛的「禁止循环依赖」：
 *
 * <ul>
 * <li>web 层不得直连 domain 的持久化接口（controller 只经 service 编排）；</li>
 * <li>凭据密钥材料只允许出现在 crypto 包内，禁止 web/service/domain 直接操作明文密钥；</li>
 * <li>包之间无循环依赖（slices 按顶层子包切分）。</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.agentflow.connector")
class ConnectorArchitectureTest {

    /** 分层：web（controller/filter）不得依赖 domain（repository 持久化），编排必须经 service。 */
    @ArchTest
    static final ArchRule webDoesNotTouchPersistence = noClasses()
            .that().resideInAPackage("com.agentflow.connector.web..")
            .should().dependOnClassesThat().resideInAPackage("com.agentflow.connector.domain..");

    /** 凭据密钥材料收敛在 crypto 包：密钥/密文参数的直接操作不得泄漏到 web/service/domain。 */
    @ArchTest
    static final ArchRule keyMaterialStaysInCrypto = noClasses()
            .that().resideOutsideOfPackage("com.agentflow.connector.crypto..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "javax.crypto.SecretKey")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "javax.crypto.spec.SecretKeySpec")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "javax.crypto.spec.GCMParameterSpec");

    /** 无包循环：按顶层子包切分（web/service/crypto/domain/tenant/config）。 */
    @ArchTest
    static final ArchRule noPackageCycles = slices()
            .matching("com.agentflow.connector.(*)..").should().beFreeOfCycles();
}
