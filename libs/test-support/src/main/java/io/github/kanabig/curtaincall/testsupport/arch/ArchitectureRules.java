package io.github.kanabig.curtaincall.testsupport.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

/**
 * 모든 모듈이 공유하는 아키텍처 규칙. 각 모듈 테스트가 {@code ArchTests.in(ArchitectureRules.class)}로 가져다 쓴다.
 *
 * <p>계층 패키지는 controller, service, repository, entity, client, messaging, scheduler, config. 해당 패키지가 없는 모듈에서는 그 계층 규칙이 비어 있는
 * 채로 통과한다(코드가 생기면 그때부터 검사된다).
 */
public final class ArchitectureRules {

    static final String ROOT = "io.github.kanabig.curtaincall.";
    static final String COMMON = "common";
    /** 서비스가 아니라 공유 라이브러리인 패키지. 서비스가 의존해도 경계 위반이 아니다. */
    static final Set<String> SHARED_LIBRARIES = Set.of(COMMON, "outbox", "testsupport");

    /** controller는 아무도 부르지 않고, client(외부·내부 API 호출)는 service에서만 호출한다. repository는 service 등 허용된 계층만 호출한다. */
    @ArchTest
    static final ArchRule LAYERS_FOLLOW_DIRECTION = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Controller")
            .definedBy("..controller..")
            .layer("Service")
            .definedBy("..service..")
            .layer("Repository")
            .definedBy("..repository..")
            .layer("Client")
            .definedBy("..client..")
            .layer("Messaging")
            .definedBy("..messaging..")
            .layer("Scheduler")
            .definedBy("..scheduler..")
            .layer("Config")
            .definedBy("..config..")
            .whereLayer("Controller")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Service")
            .mayOnlyBeAccessedByLayers("Controller", "Messaging", "Scheduler", "Config")
            .whereLayer("Client")
            .mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Repository")
            .mayOnlyBeAccessedByLayers("Service", "Messaging", "Scheduler", "Config");

    /** entity는 controller·service·repository를 몰라야 한다. */
    @ArchTest
    static final ArchRule ENTITY_DOES_NOT_DEPEND_ON_UPPER_LAYERS = classes()
            .that()
            .resideInAPackage("..entity..")
            .should()
            .onlyDependOnClassesThat()
            .resideOutsideOfPackages("..controller..", "..service..", "..repository..")
            .allowEmptyShould(true);

    /** 서비스끼리 패키지로 참조하지 않는다(서비스 경계). common 하위 패키지만 예외이고, common은 서비스 패키지를 참조할 수 없다. */
    @ArchTest
    static final ArchRule MODULES_DO_NOT_DEPEND_ON_OTHER_SERVICES =
            classes().should(notDependOnOtherModules()).allowEmptyShould(true);

    private static ArchCondition<JavaClass> notDependOnOtherModules() {
        return new ArchCondition<>("not depend on another service package (only shared libraries are allowed)") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String origin = module(item.getPackageName());
                if (origin == null) {
                    return;
                }
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    String target = module(dependency.getTargetClass().getPackageName());
                    if (target != null && !target.equals(origin) && !SHARED_LIBRARIES.contains(target)) {
                        events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** io.github.kanabig.curtaincall.<module>.… 에서 module 부분. 프로젝트 패키지가 아니면 null. */
    static String module(String packageName) {
        String prefixed = packageName + ".";
        if (!prefixed.startsWith(ROOT)) {
            return null;
        }
        String rest = prefixed.substring(ROOT.length());
        int end = rest.indexOf('.');
        return end < 0 ? null : rest.substring(0, end);
    }

    private ArchitectureRules() {}
}
