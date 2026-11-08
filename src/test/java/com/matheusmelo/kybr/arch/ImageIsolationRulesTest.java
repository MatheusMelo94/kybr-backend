package com.matheusmelo.kybr.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Component;

/**
 * Photos are decoded only by the sanitizer service, which runs in the isolated scanner organisation
 * from its own entry point. The API's classes never touch an image decoder, never depend on the
 * sanitizer, and the API's component scan can never pick a sanitizer class up.
 */
@AnalyzeClasses(packages = "com.matheusmelo.kybr", importOptions = ImportOption.DoNotIncludeTests.class)
public class ImageIsolationRulesTest {

    static final String SANITIZER = "com.matheusmelo.kybr.sanitizer..";

    @ArchTest
    public static final ArchRule NO_IMAGE_DECODING_OUTSIDE_THE_SANITIZER = noImageDecodingOutside(SANITIZER);

    @ArchTest
    public static final ArchRule THE_API_DOES_NOT_DEPEND_ON_THE_SANITIZER = noDependencyOn(SANITIZER);

    @ArchTest
    public static final ArchRule THE_SANITIZER_IS_NEVER_COMPONENT_SCANNED = neverComponentScanned(SANITIZER);

    static ArchRule noImageDecodingOutside(String sanitizer) {
        return noClasses()
                .that().resideOutsideOfPackage(sanitizer)
                .should().dependOnClassesThat().resideInAnyPackage("javax.imageio..", "java.awt..", "sun.awt..",
                        "com.sun.imageio..")
                .because("the API never loads an image decoder; photos are decoded only in the isolated sanitizer");
    }

    static ArchRule noDependencyOn(String sanitizer) {
        return noClasses()
                .that().resideOutsideOfPackage(sanitizer)
                .should().dependOnClassesThat().resideInAPackage(sanitizer)
                .because("the sanitizer is a separate process; the API reaches it only over the scan link");
    }

    static ArchRule neverComponentScanned(String sanitizer) {
        return noClasses()
                .that().resideInAPackage(sanitizer)
                .should().beAnnotatedWith(Component.class)
                .orShould().beMetaAnnotatedWith(Component.class)
                .because("the API scans every package below its own; a stereotype here would load the sanitizer"
                        + " into the API (beans are declared by the sanitizer's entry point instead)");
    }
}
