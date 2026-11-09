package com.airca.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Reusable base class for PostgreSQL integration tests utilizing Testcontainers.
 * Configured with Postgres 16 and pgvector support, migration/application credentials,
 * dynamic property injection for Spring Boot, and disabled-without-Docker behavior.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration")
public abstract class AbstractPostgresIntegrationTest {

    public static final String PGVECTOR_IMAGE_NAME = "pgvector/pgvector:pg16";
    public static final DockerImageName PGVECTOR_IMAGE = DockerImageName.parse(PGVECTOR_IMAGE_NAME)
            .asCompatibleSubstituteFor("postgres");

    public static final String DATABASE_NAME = "airca";
    public static final String MIGRATION_USER = "airca";
    public static final String MIGRATION_PASSWORD = "airca";
    public static final String APP_USER = "airca_app";
    public static final String APP_PASSWORD = "airca_app";

    @Container
    public static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withDatabaseName(DATABASE_NAME)
            .withUsername(MIGRATION_USER)
            .withPassword(MIGRATION_PASSWORD);

    @DynamicPropertySource
    public static void configurePostgresProperties(DynamicPropertyRegistry registry) {
        if (!isDockerAvailable()) {
            return;
        }
        if (!postgres.isRunning()) {
            postgres.start();
        }

        registry.add("spring.datasource.url", AbstractPostgresIntegrationTest::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractPostgresIntegrationTest::getAppUser);
        registry.add("spring.datasource.password", AbstractPostgresIntegrationTest::getAppPassword);
        registry.add("spring.datasource.hikari.initialization-fail-timeout", () -> "60000");

        registry.add("spring.liquibase.url", AbstractPostgresIntegrationTest::getJdbcUrl);
        registry.add("spring.liquibase.user", AbstractPostgresIntegrationTest::getMigrationUser);
        registry.add("spring.liquibase.password", AbstractPostgresIntegrationTest::getMigrationPassword);
        registry.add("spring.liquibase.parameters.app_user", AbstractPostgresIntegrationTest::getAppUser);
        registry.add("spring.liquibase.parameters.app_password", AbstractPostgresIntegrationTest::getAppPassword);

        // Disable scheduled retention cleanup in integration test profile
        registry.add("app.retention.enabled", () -> "false");
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String getJdbcUrl() {
        String override = System.getProperty("test.pg.url");
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (postgres.isRunning()) {
            return postgres.getJdbcUrl();
        }
        return null;
    }

    public static String getMigrationUser() {
        return System.getProperty("test.pg.migration.user", MIGRATION_USER);
    }

    public static String getMigrationPassword() {
        return System.getProperty("test.pg.migration.password", MIGRATION_PASSWORD);
    }

    public static String getAppUser() {
        return System.getProperty("test.pg.app.user", APP_USER);
    }

    public static String getAppPassword() {
        return System.getProperty("test.pg.app.password", APP_PASSWORD);
    }

    public static Connection createMigrationConnection() throws SQLException {
        return DriverManager.getConnection(getJdbcUrl(), getMigrationUser(), getMigrationPassword());
    }

    public static Connection createAppConnection() throws SQLException {
        return DriverManager.getConnection(getJdbcUrl(), getAppUser(), getAppPassword());
    }

    public static void runLiquibaseMigrations() throws Exception {
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
}
