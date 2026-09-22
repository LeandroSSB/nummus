package com.leandrossb.nummus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Machine-checked module boundaries of the modular monolith. Production
 * classes only — tests may use anything.
 */
@AnalyzeClasses(packages = "com.leandrossb.nummus", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

  @ArchTest
  static final ArchRule accountsNeverTouchLedgerInfrastructure =
      noClasses().that().resideInAPackage("..accounts..")
          .should().dependOnClassesThat().resideInAPackage("..ledger.infrastructure..");

  @ArchTest
  static final ArchRule ledgerNeverTouchesAccounts =
      noClasses().that().resideInAPackage("..ledger..")
          .should().dependOnClassesThat().resideInAPackage("..accounts..");

  @ArchTest
  static final ArchRule domainPackagesStayFrameworkFree =
      noClasses().that().resideInAnyPackage("..ledger.domain..", "..accounts.domain..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "java.sql..", "jakarta.persistence..");

  @ArchTest
  static final ArchRule persistenceTypesOnlyInInfrastructure =
      noClasses().that().resideOutsideOfPackage("..infrastructure..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("org.springframework.jdbc..", "java.sql..");

  @ArchTest
  static final ArchRule paymentsNeverTouchForeignInfrastructure =
      noClasses().that().resideInAPackage("..payments..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..ledger.infrastructure..", "..accounts.infrastructure..",
              "..psp_simulator..");

  @ArchTest
  static final ArchRule simulatorTouchesOnlyTheNetworkPort =
      noClasses().that().resideInAPackage("..psp_simulator..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..payments.domain..", "..payments.infrastructure..",
              "..payments.interfaces..", "..accounts..");

  @ArchTest
  static final ArchRule foreignModulesNeverTouchPayments =
      noClasses().that().resideInAnyPackage("..ledger..", "..accounts..")
          .should().dependOnClassesThat().resideInAPackage("..payments..");

  @ArchTest
  static final ArchRule newDomainPackagesStayFrameworkFree =
      noClasses().that().resideInAnyPackage("..payments.domain..", "..psp_simulator.domain..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "java.sql..", "jakarta.persistence..");

  @ArchTest
  static final ArchRule webhooksTouchesOnlyThePaymentsPort =
      noClasses().that().resideInAPackage("..webhooks..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..payments.domain..", "..payments.infrastructure..",
              "..payments.interfaces..", "..accounts..");

  @ArchTest
  static final ArchRule conciliationTouchesOnlyModuleApis =
      noClasses().that().resideInAPackage("..conciliation..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..payments.domain..", "..payments.infrastructure..",
              "..payments.interfaces..", "..psp_simulator.domain..",
              "..psp_simulator.infrastructure..", "..psp_simulator.interfaces..",
              "..accounts..", "..webhooks..", "..ledger.infrastructure..");

  @ArchTest
  static final ArchRule merchantsStaySelfContained =
      noClasses().that().resideInAPackage("..merchants..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..accounts..", "..ledger..", "..payments..",
              "..webhooks..", "..conciliation..", "..psp_simulator..");

  @ArchTest
  static final ArchRule businessModulesNeverTouchMerchants =
      noClasses().that().resideInAnyPackage("..accounts..", "..payments..", "..webhooks..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..merchants.domain..", "..merchants.infrastructure..",
              "..merchants.interfaces..");

  @ArchTest
  static final ArchRule auditStaysSelfContained =
      noClasses().that().resideInAPackage("..audit..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..accounts..", "..ledger..", "..payments..",
              "..webhooks..", "..conciliation..", "..psp_simulator..", "..merchants..");

  @ArchTest
  static final ArchRule foreignModulesTouchOnlyTheAuditApplicationPort =
      noClasses().that().resideOutsideOfPackage("..audit..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..audit.infrastructure..", "..audit.interfaces..");
}
