package com.airca.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.airca.integration.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgreSqlRlsVerificationTest extends AbstractPostgresIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static boolean postgresAvailable = false;
    private static UUID testIncidentId;

    @BeforeAll
    static void checkPostgreSqlAvailability() {
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
        }
    }

    @AfterAll
    static void cleanUpVerificationIncident() throws Exception {
        if (!postgresAvailable || testIncidentId == null) {
            return;
        }

        try (Connection conn = createAppConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement context = conn.prepareStatement(
                    "SELECT set_config('app.current_tenant', ?, true)")) {
                context.setString(1, TENANT_A.toString());
                context.executeQuery();
            }
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM incidents WHERE id = ?")) {
                delete.setObject(1, testIncidentId);
                delete.executeUpdate();
            }
            conn.commit();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Run Liquibase changelog master (001-006) as migration user")
    void executeLiquibaseMigrations() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL 16 Testcontainer must be running to execute DB verification");

        try (Connection conn = createMigrationConnection()) {
            JdbcConnection jdbcConnection = new JdbcConnection(conn);
            Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(),
                    jdbcConnection);
            liquibase.setChangeLogParameter("app_user", getAppUser());
            liquibase.setChangeLogParameter("app_password", getAppPassword());
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }

    @Test
    @Order(2)
    @DisplayName("Verify role attributes: airca_app is NOBYPASSRLS, NOSUPERUSER, NOCREATEROLE, NOCREATEDB")
    void verifyRoleAttributes() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL 16 Testcontainer must be running to execute DB verification");

        try (Connection conn = createMigrationConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolcanlogin, rolbypassrls "
                             + "FROM pg_roles WHERE rolname IN (?, ?)")) {
            ps.setString(1, getMigrationUser());
            ps.setString(2, getAppUser());

            boolean foundApp = false;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rolname = rs.getString("rolname");
                    boolean rolsuper = rs.getBoolean("rolsuper");
                    boolean rolcreaterole = rs.getBoolean("rolcreaterole");
                    boolean rolcreatedb = rs.getBoolean("rolcreatedb");
                    boolean rolcanlogin = rs.getBoolean("rolcanlogin");
                    boolean rolbypassrls = rs.getBoolean("rolbypassrls");

                    if (getAppUser().equals(rolname)) {
                        foundApp = true;
                        assertTrue(rolcanlogin, "airca_app must have LOGIN permission");
                        assertFalse(rolsuper, "airca_app must be NOSUPERUSER");
                        assertFalse(rolcreaterole, "airca_app must be NOCREATEROLE");
                        assertFalse(rolcreatedb, "airca_app must be NOCREATEDB");
                        assertFalse(rolbypassrls, "airca_app must be NOBYPASSRLS");
                    }
                }
            }
            assertTrue(foundApp, "airca_app role must exist in pg_roles");
        }
    }

    @Test
    @Order(3)
    @DisplayName("Verify table properties: FORCE RLS enabled on incidents & runbook_chunks, disabled on tenants")
    void verifyTableRlsAttributes() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL 16 Testcontainer must be running to execute DB verification");

        try (Connection conn = createMigrationConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity, r.rolname AS owner "
                             + "FROM pg_class c "
                             + "JOIN pg_roles r ON c.relowner = r.oid "
                             + "WHERE c.relname IN ('incidents', 'runbook_chunks', 'tenants')")) {

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String relname = rs.getString("relname");
                    boolean rls = rs.getBoolean("relrowsecurity");
                    boolean forceRls = rs.getBoolean("relforcerowsecurity");
                    String owner = rs.getString("owner");

                    assertEquals(getMigrationUser(), owner, "Table " + relname + " must be owned by " + getMigrationUser());

                    if ("incidents".equals(relname) || "runbook_chunks".equals(relname)) {
                        assertTrue(rls, "Table " + relname + " must have row security enabled");
                        assertTrue(forceRls, "Table " + relname + " must have FORCE row security enabled");
                    } else if ("tenants".equals(relname)) {
                        assertFalse(rls, "Table tenants must not have row security enabled (tenant registry)");
                    }
                }
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("Verify fail-closed behavior: runtime app login with missing/empty tenant context yields 0 rows")
    void verifyFailClosedAccessWithEmptyTenantContext() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL 16 Testcontainer must be running to execute DB verification");

        try (Connection conn = createAppConnection()) {
            // 1. Without calling set_config at all: incidents yields 0 rows
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM incidents")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Incidents query without context must return 0 rows (fail-closed)");
            }

            // 2. Setting empty string: set_config('app.current_tenant', '', true) yields 0 rows
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.current_tenant', '', true)");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM incidents")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Incidents query with empty context must return 0 rows (fail-closed)");
            }

            // 3. Insert attempt with empty tenant context fails with RLS check violation
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO incidents (tenant_id, fingerprint_hash, service_name, raw_stack_trace, sanitized_stack_trace) "
                                + "VALUES (?, 'hash1', 'test-svc', 'raw', 'sanitized')")) {
                    ps.setObject(1, TENANT_A);
                    ps.executeUpdate();
                }
            }, "Inserting incident with empty tenant context must violate RLS policy");

            conn.rollback();
            conn.setAutoCommit(true);

            // 4. Non-RLS tenants table remains accessible
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM tenants")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 2, "Tenant registry must be accessible without RLS restrictions");
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("Verify cross-tenant isolation and transaction locality for airca_app")
    void verifyTenantIsolationAndTransactionLocality() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL 16 Testcontainer must be running to execute DB verification");

        testIncidentId = UUID.randomUUID();
        String fingerprint = "fp-" + UUID.randomUUID();
        try (Connection conn = createAppConnection()) {
            // 1. Transaction 1: Insert incident for Tenant A and commit
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.current_tenant', ?, true)")) {
                ps.setString(1, TENANT_A.toString());
                ps.executeQuery();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO incidents (id, tenant_id, fingerprint_hash, service_name, raw_stack_trace, sanitized_stack_trace) "
                            + "VALUES (?, ?, ?, 'order-service', 'errA', 'errA')")) {
                ps.setObject(1, testIncidentId);
                ps.setObject(2, TENANT_A);
                ps.setString(3, fingerprint);
                int rows = ps.executeUpdate();
                assertEquals(1, rows);
            }
            conn.commit();

            // 2. Transaction 2: Scoped to Tenant A, attempt cross-tenant insert for Tenant B -> must fail with RLS check violation
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.current_tenant', ?, true)")) {
                ps.setString(1, TENANT_A.toString());
                ps.executeQuery();
            }

            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO incidents (id, tenant_id, fingerprint_hash, service_name, raw_stack_trace, sanitized_stack_trace) "
                                + "VALUES (?, ?, 'fp-tenant-b', 'wallet-service', 'errB', 'errB')")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, TENANT_B);
                    ps.executeUpdate();
                }
            }, "Cross-tenant insert must fail with RLS check violation");
            conn.rollback();

            // 3. In a new transaction without set_config: verify transaction locality reset after commit/rollback
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT current_setting('app.current_tenant', true)")) {
                assertTrue(rs.next());
                String current = rs.getString(1);
                assertTrue(current == null || current.isEmpty(),
                        "app.current_tenant setting must be transaction-local and reset after transaction boundary");
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM incidents")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1),
                        "Without tenant context in new transaction, RLS must fail-closed yielding 0 rows");
            }

            // 4. Transaction 3: Querying scoped to Tenant A sees incidentAId
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.current_tenant', ?, true)")) {
                ps.setString(1, TENANT_A.toString());
                ps.executeQuery();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM incidents WHERE id = ?")) {
                ps.setObject(1, testIncidentId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "Tenant A must see its own inserted incident");
                }
            }
            conn.commit();

            // 5. Transaction 4: Querying scoped to Tenant B does NOT see Tenant A's incident
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.current_tenant', ?, true)")) {
                ps.setString(1, TENANT_B.toString());
                ps.executeQuery();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM incidents WHERE id = ?")) {
                ps.setObject(1, testIncidentId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "Tenant B must NOT see Tenant A's incident");
                }
            }
            conn.commit();
        }
    }

    @Test
    @Order(6)
    @DisplayName("Verify airca_app cannot execute unauthorized DDL (CREATE TABLE / ALTER ROLE)")
    void verifyAppRoleCannotExecuteDdl() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL 16 Testcontainer must be running to execute DB verification");

        try (Connection conn = createAppConnection()) {
            // Cannot CREATE TABLE in public schema
            assertThrows(SQLException.class, () -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE evil_table (id INT)");
                }
            }, "airca_app must NOT have permission to CREATE TABLE");

            // Cannot ALTER ROLE to grant BYPASSRLS
            assertThrows(SQLException.class, () -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER ROLE " + getAppUser() + " BYPASSRLS");
                }
            }, "airca_app must NOT have permission to ALTER ROLE");
        }
    }
}
