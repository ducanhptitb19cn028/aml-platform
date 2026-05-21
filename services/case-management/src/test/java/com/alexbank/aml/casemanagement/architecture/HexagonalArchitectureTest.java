package com.alexbank.aml.casemanagement.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural fitness functions. These enforce the hexagonal boundaries
 * automatically — so the domain stays clean even after years of edits.
 *
 * Without these, every long-lived codebase eventually grows a Spring
 * annotation inside the domain and the testability collapses.
 */
@AnalyzeClasses(
        packages = "com.alexbank.aml.casemanagement",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "org.hibernate.."
                    )
                    .as("Domain layer must remain free of Spring/JPA. " +
                        "If this fails, an annotation or framework " +
                        "dependency leaked into pure domain code.");

    @ArchTest
    static final ArchRule application_must_not_depend_on_infrastructure =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_infrastructure =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule layered_architecture_is_respected =
            classes()
                    .that().resideInAPackage("..domain..")
                    .should().onlyBeAccessed().byAnyPackage(
                            "..domain..", "..application..", "..infrastructure.."
                    );
}
