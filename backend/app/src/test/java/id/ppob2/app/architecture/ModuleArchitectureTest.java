package id.ppob2.app.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * PRD Section 20.2's module-dependency graph is already enforced at the module level by the
 * Gradle project-dependency edges declared in each module's {@code build.gradle.kts} (the larger
 * risk — a module importing another it has no edge to fails to compile at all). This is the
 * smaller, previously-unenforced risk one level down: within a module, is a JPA entity actually
 * kept inside its {@code domain} package, the convention every module in this codebase follows
 * today? Nothing broke that convention when this rule was added (see the README's Admin RBAC
 * gap-audit entry) — this rule exists so a future change can't drift from it silently.
 *
 * <p>Runs in {@code app} (not a lower module) because {@code app} is the only module wired to
 * every other module (Section 20.2's composition root) and can therefore import the whole
 * {@code id.ppob2} package tree via {@link ClassFileImporter} in one scan.
 *
 * <p>Deliberately one rule, not an attempt to encode the entire Section 20.2 graph — that graph is
 * already enforced (and more cheaply) by Gradle itself; duplicating it here in ArchUnit form would
 * be redundant enforcement of the smaller risk, not coverage of a new one.
 */
class ModuleArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter().importPackages("id.ppob2");

    @Test
    void jpaEntitiesLiveOnlyInDomainPackages() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(Entity.class)
                .should().resideInAPackage("..domain..");

        rule.check(classes);
    }
}
