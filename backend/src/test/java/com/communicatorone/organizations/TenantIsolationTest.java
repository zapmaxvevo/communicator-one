package com.communicatorone.organizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.communicatorone.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TenantIsolationTest {

    @Autowired
    private CurrentOrganization currentOrganization;

    @Autowired
    private IsolationProbeRepository probes;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID acme;
    private UUID globex;
    private UUID acmeRecord;
    private UUID globexRecord;

    @BeforeEach
    void createTwoOrganizationsWithOneRecordEach() {
        acme = newOrganization("Acme");
        globex = newOrganization("Globex");
        acmeRecord = storedRecordOf(acme, "Acme record");
        globexRecord = storedRecordOf(globex, "Globex record");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void organizationSeesOnlyItsOwnRecords() {
        // When
        var visibleRecords = currentOrganization.callAs(acme, () -> idsOf(probes.findAll()));
        var visibleCount = currentOrganization.callAs(acme, probes::count);

        // Then
        assertThat(visibleRecords).containsExactly(acmeRecord);
        assertThat(visibleCount).isEqualTo(1);
    }

    @Test
    void recordOfAnotherOrganizationIsNotFoundById() {
        // When
        var ownRecord = currentOrganization.callAs(acme, () -> probes.findById(acmeRecord));
        var otherOrganizationRecord = currentOrganization.callAs(acme, () -> probes.findById(globexRecord));

        // Then
        assertThat(ownRecord).isPresent();
        assertThat(otherOrganizationRecord).isEmpty();
    }

    @Test
    void newRecordBelongsToTheOrganizationInScope() {
        // When
        var newRecord = currentOrganization.callAs(acme,
                () -> probes.save(IsolationProbe.labeled("New Acme record")).id());

        // Then
        assertThat(organizationOf(newRecord)).isEqualTo(acme);
    }

    @Test
    void authenticatedMemberSeesOnlyTheirOrganizationRecords() {
        // Given
        signInAsMemberOf(globex);

        // When
        var visibleRecords = idsOf(probes.findAll());

        // Then
        assertThat(visibleRecords).containsExactly(globexRecord);
    }

    @Test
    void unauthenticatedMemberSeesNoRecords() {
        // Given
        signIn(UsernamePasswordAuthenticationToken.unauthenticated(new Member(globex), null));

        // When
        var visibleRecords = probes.findAll();

        // Then
        assertThat(visibleRecords).isEmpty();
    }

    @Test
    void explicitScopeTakesPrecedenceOverAuthenticatedMember() {
        // Given
        signInAsMemberOf(globex);

        // When
        var visibleRecords = currentOrganization.callAs(acme, () -> idsOf(probes.findAll()));

        // Then
        assertThat(visibleRecords).containsExactly(acmeRecord);
    }

    @Test
    void withoutOrganizationNoRecordIsVisible() {
        // When
        var visibleRecords = probes.findAll();

        // Then
        assertThat(visibleRecords).isEmpty();
    }

    @Test
    void recordCannotBeStoredWithoutOrganization() {
        // When
        var rejection = catchThrowable(() -> probes.save(IsolationProbe.labeled("Orphan record")));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(recordsLabeled("Orphan record")).isZero();
    }

    @Test
    void organizationCannotBeSwitchedInsideATransaction() {
        // When
        var rejection = catchThrowable(() -> currentOrganization.runAs(acme,
                () -> transactionTemplate.executeWithoutResult(transaction -> currentOrganization.runAs(globex,
                        () -> probes.save(IsolationProbe.labeled("Switched record"))))));

        // Then
        assertThat(rejection).isInstanceOf(IllegalStateException.class);
        assertThat(recordsLabeled("Switched record")).isZero();
    }

    @Test
    void organizationCannotBeSwitchedInsideATransactionalScopeWithoutActualTransaction() {
        // Given
        var supports = new TransactionTemplate(transactionTemplate.getTransactionManager());
        supports.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);

        // When
        var rejection = catchThrowable(() -> currentOrganization.runAs(acme,
                () -> supports.executeWithoutResult(scope -> currentOrganization.runAs(globex,
                        () -> probes.save(IsolationProbe.labeled("Switched record without transaction"))))));

        // Then
        assertThat(rejection).isInstanceOf(IllegalStateException.class);
        assertThat(recordsLabeled("Switched record without transaction")).isZero();
    }

    @Test
    void organizationIsRequiredToRunInItsScope() {
        // Given
        var actionRan = new AtomicBoolean(false);

        // When
        var runRejection = catchThrowable(() -> currentOrganization.runAs(null, () -> actionRan.set(true)));
        var callRejection = catchThrowable(() -> currentOrganization.callAs(null, () -> {
            actionRan.set(true);
            return null;
        }));

        // Then
        assertThat(runRejection).isInstanceOf(NullPointerException.class);
        assertThat(callRejection).isInstanceOf(NullPointerException.class);
        assertThat(actionRan).isFalse();
    }

    @Test
    void everyBusinessEntityBelongsToAnOrganization() {
        // Given
        var mappingMetamodel = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactoryImplementor.class)
                .getMappingMetamodel();

        // When
        var businessEntities = mappingMetamodel.streamEntityDescriptors()
                .filter(entity -> entity.getJavaType().getJavaTypeClass().getPackageName()
                        .startsWith("com.communicatorone."))
                .filter(entity -> entity.getJavaType().getJavaTypeClass() != Organization.class)
                .toList();
        var entitiesWithoutOrganization = businessEntities.stream()
                .filter(entity -> entity.getTenantIdMapping() == null)
                .map(EntityPersister::getEntityName)
                .toList();

        // Then
        assertThat(businessEntities).extracting(EntityPersister::getEntityName)
                .contains(IsolationProbe.class.getName());
        assertThat(entitiesWithoutOrganization).isEmpty();
    }

    private UUID newOrganization(String name) {
        return transactionTemplate.execute(transaction -> {
            var organization = Organization.named(name);
            entityManager.persist(organization);
            return organization.id();
        });
    }

    private UUID storedRecordOf(UUID organizationId, String label) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO isolation_probes (id, label, organization_id) VALUES (?, ?, ?)",
                id, label, organizationId);
        return id;
    }

    private UUID organizationOf(UUID recordId) {
        return jdbcTemplate.queryForObject("SELECT organization_id FROM isolation_probes WHERE id = ?",
                UUID.class, recordId);
    }

    private int recordsLabeled(String label) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM isolation_probes WHERE label = ?",
                Integer.class, label);
    }

    private static List<UUID> idsOf(List<IsolationProbe> records) {
        return records.stream().map(IsolationProbe::id).toList();
    }

    private static void signInAsMemberOf(UUID organizationId) {
        signIn(UsernamePasswordAuthenticationToken.authenticated(new Member(organizationId), null, List.of()));
    }

    private static void signIn(Authentication authentication) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private record Member(UUID organizationId) implements OrganizationMember {
    }
}
