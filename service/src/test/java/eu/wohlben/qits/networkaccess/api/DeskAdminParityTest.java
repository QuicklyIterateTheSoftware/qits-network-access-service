package eu.wohlben.qits.networkaccess.api;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.archrules.DeskAdminParityRules;
import java.util.Set;

/**
 * Wherever {@code qits:admin-agent} is admitted, so is {@code qits:desk-admin} — at class level
 * (the READ set) and on every write method whose own {@code @RolesAllowed} replaces it (the WRITE
 * set). No refusal is recorded for this service.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.networkaccess.api",
    importOptions = ImportOption.DoNotIncludeTests.class)
class DeskAdminParityTest {

  private static final Set<String> REFUSED = Set.of();

  @ArchTest
  static final ArchRule PARITY = DeskAdminParityRules.everyAdminAgentRouteAdmitsDeskAdmin(REFUSED);

  @ArchTest
  static final ArchRule REFUSALS = DeskAdminParityRules.refusedRoutesExistAndRefuse(REFUSED);
}
