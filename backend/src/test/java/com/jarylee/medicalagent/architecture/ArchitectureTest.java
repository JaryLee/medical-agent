package com.jarylee.medicalagent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test
    void controllersDoNotDependOnModelOrExternalToolImplementations() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.jarylee.medicalagent");
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..agent.mock..",
                        "..literature..")
                .check(classes);
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "com.jarylee.medicalagent.document.ControlledDocxTemplateEngine")
                .check(classes);
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "com.jarylee.medicalagent.file.MinioObjectStorage")
                .check(classes);
    }
}
