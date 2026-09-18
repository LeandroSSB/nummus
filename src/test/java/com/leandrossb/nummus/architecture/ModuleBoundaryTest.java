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
}
