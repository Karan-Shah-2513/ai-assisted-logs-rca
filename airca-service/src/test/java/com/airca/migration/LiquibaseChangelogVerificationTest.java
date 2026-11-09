package com.airca.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LiquibaseChangelogVerificationTest {

    @Test
    void changelogMasterIncludesAllIncrementalMigrations() throws Exception {
        ClassPathResource masterResource = new ClassPathResource("db/changelog/db.changelog-master.xml");
        assertTrue(masterResource.exists(), "db.changelog-master.xml must exist");

        String masterXml;
        try (InputStream is = masterResource.getInputStream()) {
            masterXml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<String> expectedChangeSets = List.of(
                "001-init-pgvector",
                "002-seed-data",
                "003-migrate-vector-768",
                "004-add-runbook-chunk-source-and-incident",
                "005-preserve-runbook-chunk-on-incident-delete",
                "006-enforce-app-role-rls"
        );

        for (String changeSetId : expectedChangeSets) {
            assertTrue(masterXml.contains("id=\"" + changeSetId + "\""),
                    "db.changelog-master.xml must register changeSet: " + changeSetId);
        }

        assertTrue(masterXml.contains("name=\"app_user\""),
                "db.changelog-master.xml must define app_user property");
        assertTrue(masterXml.contains("name=\"app_password\""),
                "db.changelog-master.xml must define app_password property");
    }

    @Test
    void migration005PreservesKnowledgeChunksWithOnDeleteSetNull() throws Exception {
        ClassPathResource res005 = new ClassPathResource("db/changelog/005-preserve-runbook-chunk-on-incident-delete.sql");
        assertTrue(res005.exists(), "005-preserve-runbook-chunk-on-incident-delete.sql must exist");

        String sql005;
        try (InputStream is = res005.getInputStream()) {
            sql005 = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        String sqlWithoutComments = sql005.replaceAll("--.*", "");
        assertTrue(sqlWithoutComments.contains("ON DELETE SET NULL"),
                "Migration 005 must specify ON DELETE SET NULL to preserve knowledge chunks when incidents are purged");
        assertTrue(sqlWithoutComments.contains("DROP CONSTRAINT"),
                "Migration 005 must drop the previous FK constraint");
        assertTrue(sqlWithoutComments.contains("source_incident_id"),
                "Migration 005 must target source_incident_id column");
        assertFalse(sqlWithoutComments.contains("ON DELETE CASCADE"),
                "Migration 005 must NOT use ON DELETE CASCADE in executable statements");
    }

    @Test
    void migration006EnforcesNobypassrlsRoleAndGrants() throws Exception {
        ClassPathResource res006 = new ClassPathResource("db/changelog/006-enforce-app-role-rls.sql");
        assertTrue(res006.exists(), "006-enforce-app-role-rls.sql must exist");

        String sql006;
        try (InputStream is = res006.getInputStream()) {
            sql006 = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        String sqlWithoutComments = sql006.replaceAll("--.*", "");
        assertTrue(sqlWithoutComments.contains("NOBYPASSRLS"),
                "Migration 006 must explicitly enforce NOBYPASSRLS for runtime application role");
        assertTrue(sqlWithoutComments.contains("NOSUPERUSER"),
                "Migration 006 must explicitly enforce NOSUPERUSER for runtime application role");
        assertTrue(sqlWithoutComments.contains("NOCREATEROLE"),
                "Migration 006 must explicitly enforce NOCREATEROLE for runtime application role");
        assertTrue(sqlWithoutComments.contains("NOCREATEDB"),
                "Migration 006 must explicitly enforce NOCREATEDB for runtime application role");

        assertTrue(sqlWithoutComments.contains("FORCE ROW LEVEL SECURITY"),
                "Migration 006 must FORCE ROW LEVEL SECURITY on protected tables");
        assertTrue(sqlWithoutComments.contains("incidents"),
                "Migration 006 must target incidents table");
        assertTrue(sqlWithoutComments.contains("runbook_chunks"),
                "Migration 006 must target runbook_chunks table");
        assertTrue(sqlWithoutComments.contains("tenants"),
                "Migration 006 must target tenants registry table");

        assertTrue(sqlWithoutComments.contains("SELECT, INSERT, UPDATE, DELETE ON TABLE incidents, runbook_chunks"),
                "Migration 006 must grant CRUD permissions on incidents and runbook_chunks");
        assertTrue(sqlWithoutComments.contains("SELECT ON TABLE tenants"),
                "Migration 006 must grant read permission on tenants registry table");
    }

    @Test
    void allChangelogSqlFilesArePresentOnClasspath() {
        List<String> sqlFiles = List.of(
                "db/changelog/001-init-pgvector.sql",
                "db/changelog/002-seed-data.sql",
                "db/changelog/003-migrate-vector-768.sql",
                "db/changelog/004-add-runbook-chunk-source-and-incident.sql",
                "db/changelog/005-preserve-runbook-chunk-on-incident-delete.sql",
                "db/changelog/006-enforce-app-role-rls.sql"
        );

        for (String path : sqlFiles) {
            ClassPathResource resource = new ClassPathResource(path);
            assertTrue(resource.exists(), "Changelog file must exist on classpath: " + path);
        }
    }
}
