package io.github.kanabig.curtaincall.queue;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import io.github.kanabig.curtaincall.testsupport.arch.ArchitectureRules;

@AnalyzeClasses(packagesOf = QueueApplication.class, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchTests RULES = ArchTests.in(ArchitectureRules.class);
}
