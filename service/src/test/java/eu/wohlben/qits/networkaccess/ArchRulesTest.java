package eu.wohlben.qits.networkaccess;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.CausationRowRules;
import eu.wohlben.qits.archrules.HttpClientRules;

/**
 * The platform's shared ArchUnit rules over every class of this component — `domain`'s entities
 * included, which is why it lives in `service`, the one classpath that carries both modules: every
 * {@code @Entity} decides about causation ({@code CausedRow} or {@code @Uncaused} with its reason),
 * and every HTTP client is the platform's.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.networkaccess",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests CAUSATION = ArchTests.in(CausationRowRules.class);

  @ArchTest static final ArchTests HTTP_CLIENTS = ArchTests.in(HttpClientRules.class);
}
