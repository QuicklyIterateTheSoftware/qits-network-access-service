package eu.wohlben.qits.networkaccess;

import eu.wohlben.qits.archrules.DatasourceBaselineRules;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Every postgresql datasource this service boots — `domain`'s and the qits-eventstream outbox's —
 * carries the three-line resilience block. A {@code @QuarkusTest}, because the rule has to judge the
 * merged configuration the application actually starts with.
 */
@QuarkusTest
public class DatasourceBaselineTest {

  @Test
  public void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
