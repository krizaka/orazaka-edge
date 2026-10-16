package com.krizaka.orazaka.edge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.krizaka.orazaka.test.architecture.ConfigBindingRules;
import com.krizaka.orazaka.test.architecture.GovernanceRules;
import com.krizaka.orazaka.test.architecture.LoggedContentRules;
import com.krizaka.orazaka.test.architecture.PackPurityRules;
import com.krizaka.orazaka.test.architecture.RunSurfaceRules;
import com.krizaka.orazaka.test.architecture.SourceFileScanner;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Governance guardrails for the orazaka-edge module: the edge is a transport-only strangler facade
 * — it must never wire the framework libraries or grow business logic.
 */
class EdgeGovernanceTest {

  /**
   * The rules below are repository-wide, not module-scoped: the worst pack coupling lives in the
   * Python media worker, which is in no Maven reactor, so a per-module scan could never see it.
   */
  private static final Path REPOSITORY_ROOT =
      PackPurityRules.locateRepositoryRoot(Path.of(System.getProperty("user.dir")));

  private static JavaClasses edgeClasses;

  @BeforeAll
  static void importClasses() {
    edgeClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.krizaka.orazaka.edge");
  }

  @Test
  @DisplayName("[DOOR-001] no inbound HTTP entry dispatches a job")
  void noInboundEntryDispatchesAJob() {
    RunSurfaceRules.assertNoInboundEntryDispatchesAJob();
  }

  @Test
  @DisplayName("[EDGE-001] The edge never depends on the framework libraries")
  void edgeNeverDependsOnFrameworkLibraries() {
    noClasses()
        .that()
        .resideInAPackage("com.krizaka.orazaka.edge..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.krizaka.orazaka.core..",
            "com.krizaka.orazaka.business..",
            "com.krizaka.users..",
            "com.krizaka.orazaka.tools..",
            "com.krizaka.orazaka.interceptor..",
            "com.krizaka.orazaka.persistence..",
            "com.krizaka.orazaka.conversationservice..")
        .because(
            "The edge is a transport-only facade in front of the constellation — it talks to"
                + " backends over HTTP, never in-process")
        .check(edgeClasses);
  }

  @Test
  @DisplayName("[GOV-001] No anonymous classes in edge production code")
  void noAnonymousClassesInProduction() {
    classes()
        .that()
        .resideInAPackage("com.krizaka.orazaka.edge..")
        .should()
        .notBeAnonymousClasses()
        .because("Anonymous classes are banned in production — use named types or lambdas")
        .check(edgeClasses);
  }

  @Test
  @DisplayName("[GOV-003] No banned literals in edge sources")
  void noBannedLiteralsInSources() {
    SourceFileScanner.assertNoBannedLiterals(Path.of("src/main/java"));
  }

  // GOV-006: [ADR-035] permitAll rule is not invoked here: the edge has no SecurityConfig by design
  // —
  // it is the facade that exchanges API keys for JWTs, and every service behind it enforces its own
  // security (AGENTS.md §8). The rule inspects SecurityConfigs; here it inspected none.

  @Test
  @DisplayName("[ADR-035] /internal/v1 demands the SERVICE authority, not merely authentication")
  void internalSurfaceDemandsServiceAuthority() {
    GovernanceRules.assertInternalSurfaceRequiresServiceAuthority(
        Path.of(System.getProperty("user.dir"), "src", "main", "java"));
  }

  @Test
  @DisplayName("[AGENTS.md §4] requests run on virtual threads")
  void requestsRunOnVirtualThreads() {
    GovernanceRules.assertVirtualThreadsEnabled(Path.of(System.getProperty("user.dir")));
  }

  @Test
  @DisplayName("[PACK-002] no pack, studio or pack-capability key is a literal in engine code")
  void noPackKeyLiteralsInEngineCode() {
    GovernanceRules.assertNoPackKeyLiterals(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[PACK-003] engine code never branches on a pack identifier")
  void noPackKeyConditionalsInEngineCode() {
    GovernanceRules.assertNoPackKeyConditionals(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[EXEC-001] every in-process capability's handler_key has an executor")
  void everyCapabilityHasAnExecutor() {
    GovernanceRules.assertEveryCapabilityHasAnExecutor(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[EXEC-002] every capability's routing_key is drained by a declared worker")
  void everyCapabilityIsDrained() {
    GovernanceRules.assertEveryCapabilityIsDrained(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName(
      "[CFG-001] every type the configuration binder builds has a constructor it can choose")
  void configurationBindsUnambiguously() {
    ConfigBindingRules.assertConfigurationBindsUnambiguously();
    ConfigBindingRules.assertInjectableComponentsHaveOneConstructor();
  }

  @Test
  @DisplayName("[ERR-113] No Environment injection in production beans")
  void noEnvironmentInjection() {
    SourceFileScanner.assertNoEnvironmentInjection(Path.of("src", "main", "java"));
  }

  /** [LOG-001] no logging call takes a prompt, a response body or a message text (ADR-064). */
  @Test
  void noLoggingCallTakesContent() {
    LoggedContentRules.assertNoLoggingCallTakesContent();
  }
}
