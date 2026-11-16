package com.airca.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.airca.integration.AbstractPostgresIntegrationTest;
import com.airca.model.Incident;
import com.airca.model.IncidentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class IncidentDecisionGatePersistenceTest extends AbstractPostgresIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static boolean postgresAvailable = false;
    private static EntityManagerFactory entityManagerFactory;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        if (!isDockerAvailable()) {
            postgresAvailable = false;
            return;
        }

        if (!postgres.isRunning()) {
            postgres.start();
        }

        try (Connection conn = createMigrationConnection()) {
            postgresAvailable = conn != null && !conn.isClosed();
        } catch (Exception ex) {
            postgresAvailable = false;
            return;
        }

        try {
            runLiquibaseMigrations();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to run Liquibase migrations", ex);
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                getJdbcUrl(), getAppUser(), getAppPassword());

        LocalContainerEntityManagerFactoryBean emfb = new LocalContainerEntityManagerFactoryBean();
        emfb.setDataSource(dataSource);
        emfb.setPackagesToScan("com.airca.model");
        emfb.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

        Properties props = new Properties();
        props.put("hibernate.hbm2ddl.auto", "validate");
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        emfb.setJpaProperties(props);
        emfb.afterPropertiesSet();

        entityManagerFactory = emfb.getObject();
    }

    @AfterAll
    static void tearDown() {
        if (entityManagerFactory != null && entityManagerFactory.isOpen()) {
            entityManagerFactory.close();
        }
    }

    @Test
    @DisplayName("Verify migration 007 created decision_gate_json column with jsonb data type")
    void verifyDecisionGateJsonColumnSchema() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL Testcontainer must be running");

        try (Connection conn = createMigrationConnection();
             PreparedStatement ps = conn.prepareStatement(
                     """
                     SELECT column_name, data_type, is_nullable
                     FROM information_schema.columns
                     WHERE table_name = 'incidents' AND column_name = 'decision_gate_json'
                     """)) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Column decision_gate_json must exist on table incidents");
                assertEquals("decision_gate_json", rs.getString("column_name"));
                assertEquals("jsonb", rs.getString("data_type").toLowerCase());
                assertEquals("YES", rs.getString("is_nullable"));
            }
        }
    }

    @Test
    @DisplayName("Verify JPA entity persistence with decisionGateJson mapping")
    void testIncidentPersistenceWithDecisionGateJson() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL Testcontainer must be running");

        UUID incidentId = UUID.randomUUID();
        String jsonPayload = """
                {
                    "decisionType": "NOUL",
                    "status": "PROCEED",
                    "criteria": {
                        "groundingConfidence": 0.94,
                        "noiseGatePassed": true
                    },
                    "reasoning": "Calibrated decision gate verified incident is not transient noise"
                }
                """;
        JsonNode decisionNode = objectMapper.readTree(jsonPayload);

        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            em.createNativeQuery("SELECT set_config('app.current_tenant', :tenant, false)")
                    .setParameter("tenant", TENANT_A.toString())
                    .getSingleResult();

            Incident incident = new Incident();
            incident.setId(incidentId);
            incident.setTenantId(TENANT_A);
            incident.setFingerprintHash("fp-" + UUID.randomUUID());
            incident.setServiceName("order-service");
            incident.setRawStackTrace("java.lang.Exception: DB timeout");
            incident.setSanitizedStackTrace("java.lang.Exception: DB timeout");
            incident.setOccurrenceCount(1);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setFirstSeen(Instant.now());
            incident.setLastSeen(Instant.now());
            incident.setDecisionGateJson(decisionNode);

            em.persist(incident);
            em.flush();
            em.getTransaction().commit();

            // Clear first-level cache to ensure subsequent read queries database
            em.clear();

            em.getTransaction().begin();
            em.createNativeQuery("SELECT set_config('app.current_tenant', :tenant, false)")
                    .setParameter("tenant", TENANT_A.toString())
                    .getSingleResult();

            Incident retrieved = em.find(Incident.class, incidentId);
            assertNotNull(retrieved, "Persisted incident must be retrievable");
            assertNotNull(retrieved.getDecisionGateJson(), "decisionGateJson must not be null");
            assertEquals("NOUL", retrieved.getDecisionGateJson().get("decisionType").asText());
            assertEquals("PROCEED", retrieved.getDecisionGateJson().get("status").asText());
            assertEquals(0.94, retrieved.getDecisionGateJson().get("criteria").get("groundingConfidence").asDouble());
            assertTrue(retrieved.getDecisionGateJson().get("criteria").get("noiseGatePassed").asBoolean());
            assertEquals("Calibrated decision gate verified incident is not transient noise",
                    retrieved.getDecisionGateJson().get("reasoning").asText());
            em.getTransaction().commit();
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }
    }

    @Test
    @DisplayName("Verify JPA entity persistence with null decisionGateJson preserves history/compatibility")
    void testIncidentPersistenceWithNullDecisionGateJson() {
        assumeTrue(postgresAvailable, "PostgreSQL Testcontainer must be running");

        UUID incidentId = UUID.randomUUID();

        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            em.createNativeQuery("SELECT set_config('app.current_tenant', :tenant, false)")
                    .setParameter("tenant", TENANT_A.toString())
                    .getSingleResult();

            Incident incident = new Incident();
            incident.setId(incidentId);
            incident.setTenantId(TENANT_A);
            incident.setFingerprintHash("fp-" + UUID.randomUUID());
            incident.setServiceName("auth-service");
            incident.setRawStackTrace("java.lang.Exception: Auth failure");
            incident.setSanitizedStackTrace("java.lang.Exception: Auth failure");
            incident.setOccurrenceCount(3);
            incident.setStatus(IncidentStatus.INVESTIGATING);
            incident.setFirstSeen(Instant.now());
            incident.setLastSeen(Instant.now());
            incident.setDecisionGateJson(null);

            em.persist(incident);
            em.flush();
            em.getTransaction().commit();

            em.clear();

            em.getTransaction().begin();
            em.createNativeQuery("SELECT set_config('app.current_tenant', :tenant, false)")
                    .setParameter("tenant", TENANT_A.toString())
                    .getSingleResult();

            Incident retrieved = em.find(Incident.class, incidentId);
            assertNotNull(retrieved);
            assertNull(retrieved.getDecisionGateJson(), "decisionGateJson can be null for legacy/un-gated incidents");
            em.getTransaction().commit();
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }
    }

    @Test
    @DisplayName("Verify tenant RLS isolation protects incidents with decision_gate_json")
    void testTenantRlsIsolationOnDecisionGateJson() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL Testcontainer must be running");

        UUID incidentId = UUID.randomUUID();
        String json = "{\"decisionType\":\"SCORE\",\"score\":88}";
        JsonNode node = objectMapper.readTree(json);

        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            // Persist as Tenant A
            em.getTransaction().begin();
            em.createNativeQuery("SELECT set_config('app.current_tenant', :tenant, false)")
                    .setParameter("tenant", TENANT_A.toString())
                    .getSingleResult();

            Incident incident = new Incident();
            incident.setId(incidentId);
            incident.setTenantId(TENANT_A);
            incident.setFingerprintHash("fp-" + UUID.randomUUID());
            incident.setServiceName("ledger-service");
            incident.setRawStackTrace("err");
            incident.setSanitizedStackTrace("err");
            incident.setOccurrenceCount(1);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setFirstSeen(Instant.now());
            incident.setLastSeen(Instant.now());
            incident.setDecisionGateJson(node);

            em.persist(incident);
            em.flush();
            em.getTransaction().commit();

            em.clear();

            // Attempt to read as Tenant B -> should be null due to RLS
            em.getTransaction().begin();
            em.createNativeQuery("SELECT set_config('app.current_tenant', :tenant, false)")
                    .setParameter("tenant", TENANT_B.toString())
                    .getSingleResult();

            Incident tenantBView = em.find(Incident.class, incidentId);
            assertNull(tenantBView, "Tenant B must not see Tenant A's incident with decision_gate_json");
            em.getTransaction().commit();

            em.clear();

            // Read again as Tenant A -> found
            em.getTransaction().begin();
            em.createNativeQuery("SELECT set_config('app.current_tenant', :tenant, false)")
                    .setParameter("tenant", TENANT_A.toString())
                    .getSingleResult();

            Incident tenantAView = em.find(Incident.class, incidentId);
            assertNotNull(tenantAView, "Tenant A must see its own incident");
            assertEquals(88, tenantAView.getDecisionGateJson().get("score").asInt());
            em.getTransaction().commit();
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }
    }
}
