package com.airca.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCommitInspectorServiceTest {

    @TempDir
    Path tempRepo;

    @BeforeEach
    void setUpGitRepo() throws Exception {
        runCommand(tempRepo, "git", "init", "-b", "main");
        runCommand(tempRepo, "git", "config", "user.name", "Test Committer");
        runCommand(tempRepo, "git", "config", "user.email", "committer@example.com");
    }

    @Test
    void committedTenantFileExtractsActualCommitMetadataAndUnifiedDiff() throws Exception {
        Path orderDir = tempRepo.resolve("tenants/tenant1-alphafin/order-service/src/main/java/com/airca/tenants/order/controller");
        Files.createDirectories(orderDir);
        Path orderFile = orderDir.resolve("OrderController.java");

        Files.writeString(orderFile, """
                package com.airca.tenants.order.controller;

                public class OrderController {
                    public void checkout() {
                        System.out.println("v1-baseline");
                    }
                }
                """, StandardCharsets.UTF_8);

        runCommand(tempRepo, "git", "add", "tenants");
        runCommand(tempRepo, "git", "commit", "-m", "feat: initial order controller");

        Files.writeString(orderFile, """
                package com.airca.tenants.order.controller;

                public class OrderController {
                    public void checkout() {
                        System.out.println("v2-defect-null-pointer");
                    }
                }
                """, StandardCharsets.UTF_8);

        runCommand(tempRepo, "git", "commit", "-am", "fix: introduce checkout regression");

        GitCommitInspectorService inspector = new GitCommitInspectorService(tempRepo);

        String stackTrace = """
                java.lang.NullPointerException: Cannot invoke method
                	at com.airca.tenants.order.controller.OrderController.checkout(OrderController.java:5)
                	at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)
                """;

        GitCommitInspectorService.GitInspectionResult result = inspector.inspect("order-service", stackTrace);

        assertEquals("tenants/tenant1-alphafin/order-service/src/main/java/com/airca/tenants/order/controller/OrderController.java",
                result.resolvedPath());
        assertTrue(result.hasCommittedHistory());
        assertEquals(2, result.commits().size());

        GitCommitInspectorService.GitCommitInfo topCommit = result.commits().get(0);
        assertTrue(topCommit.commitHash().matches("[0-9a-f]{40}"), "Commit hash should be full SHA: " + topCommit.commitHash());
        assertEquals("Test Committer <committer@example.com>", topCommit.author());
        assertTrue(topCommit.timestamp().contains("T"), "Timestamp should be ISO format: " + topCommit.timestamp());
        assertEquals("fix: introduce checkout regression", topCommit.message());
        assertTrue(topCommit.diff().contains("-        System.out.println(\"v1-baseline\");"));
        assertTrue(topCommit.diff().contains("+        System.out.println(\"v2-defect-null-pointer\");"));

        GitCommitInspectorService.GitCommitInfo initialCommit = result.commits().get(1);
        assertEquals("feat: initial order controller", initialCommit.message());
        assertTrue(initialCommit.diff().contains("+public class OrderController"));

        String relevantDiff = inspector.findRelevantDiff("order-service", stackTrace);
        assertTrue(relevantDiff.contains("Source: tenants/tenant1-alphafin/order-service/src/main/java/com/airca/tenants/order/controller/OrderController.java"));
        assertTrue(relevantDiff.contains("2 commits found"));
        assertTrue(relevantDiff.contains(topCommit.commitHash()));
        assertTrue(relevantDiff.contains("Test Committer <committer@example.com>"));
        assertTrue(relevantDiff.contains("fix: introduce checkout regression"));
        assertTrue(relevantDiff.contains("+        System.out.println(\"v2-defect-null-pointer\");"));
    }

    @Test
    void untrackedTenantFileReturnsHonestWorkingTreeDiffWithNoFakeMetadata() throws Exception {
        Path readme = tempRepo.resolve("README.md");
        Files.writeString(readme, "# Project\n", StandardCharsets.UTF_8);
        runCommand(tempRepo, "git", "add", "README.md");
        runCommand(tempRepo, "git", "commit", "-m", "chore: repo root setup");

        Path authDir = tempRepo.resolve("tenants/tenant2-betapay/auth-service/src/main/java/com/airca/tenants/auth/controller");
        Files.createDirectories(authDir);
        Path authFile = authDir.resolve("AuthController.java");

        Files.writeString(authFile, """
                package com.airca.tenants.auth.controller;

                public class AuthController {
                    public void authenticate() {
                        throw new NullPointerException("untracked defect");
                    }
                }
                """, StandardCharsets.UTF_8);

        GitCommitInspectorService inspector = new GitCommitInspectorService(tempRepo);

        String stackTrace = """
                java.lang.NullPointerException: untracked defect
                	at com.airca.tenants.auth.controller.AuthController.authenticate(AuthController.java:5)
                """;

        GitCommitInspectorService.GitInspectionResult result = inspector.inspect("auth-service", stackTrace);

        assertEquals("tenants/tenant2-betapay/auth-service/src/main/java/com/airca/tenants/auth/controller/AuthController.java",
                result.resolvedPath());
        assertFalse(result.hasCommittedHistory());
        assertTrue(result.commits().isEmpty(), "No fake commits should be present");

        assertNotNull(result.workingTreeDiff());
        assertTrue(result.workingTreeDiff().contains("diff --git"));
        assertTrue(result.workingTreeDiff().contains("+public class AuthController"));

        String report = result.plainReport();
        assertTrue(report.contains("No committed git history found for this path."));
        assertTrue(report.contains("Working tree diff:"));
        assertTrue(report.contains("+public class AuthController"));

        assertFalse(report.contains("Commit a9f120e"));
        assertFalse(report.contains("alice@alphafin.com"));
        assertFalse(report.contains("maya@alphafin.com"));
    }

    @Test
    void preventsPathTraversalAttemptsInStackTrace() throws Exception {
        Path ledgerDir = tempRepo.resolve("tenants/tenant1-alphafin/ledger-service/src/main/java/com/airca/tenants/ledger/controller");
        Files.createDirectories(ledgerDir);
        Path ledgerFile = ledgerDir.resolve("LedgerController.java");
        Files.writeString(ledgerFile, "public class LedgerController {}", StandardCharsets.UTF_8);

        GitCommitInspectorService inspector = new GitCommitInspectorService(tempRepo);

        String malicious1 = """
                java.lang.Exception: exploit
                	at ../../../etc/passwd.read(passwd.java:1)
                """;
        String resolvedPath1 = inspector.resolveSafeTenantSourcePath("ledger-service", malicious1);
        assertTrue(resolvedPath1 == null || resolvedPath1.startsWith("tenants/"));

        String malicious2 = """
                java.lang.Exception: exploit
                	at com.airca.tenants..traversal.Malicious.hack(Malicious.java:1)
                """;
        String resolvedPath2 = inspector.resolveSafeTenantSourcePath("ledger-service", malicious2);
        assertTrue(resolvedPath2 == null || resolvedPath2.startsWith("tenants/"));

        String malicious3 = """
                java.lang.Exception: exploit
                	at com.airca.tenants.BadClass.run(../../etc/shadow.java:1)
                """;
        String resolvedPath3 = inspector.resolveSafeTenantSourcePath("ledger-service", malicious3);
        assertTrue(resolvedPath3 == null || resolvedPath3.startsWith("tenants/"));

        String legitimate = """
                java.lang.Exception: normal
                	at com.airca.tenants.ledger.controller.LedgerController.transfer(LedgerController.java:1)
                """;
        String resolvedLegitimate = inspector.resolveSafeTenantSourcePath("ledger-service", legitimate);
        assertEquals("tenants/tenant1-alphafin/ledger-service/src/main/java/com/airca/tenants/ledger/controller/LedgerController.java",
                resolvedLegitimate);
    }

    @Test
    void unresolvedTenantSourceReportsPlainlyWithoutFabrication() {
        GitCommitInspectorService inspector = new GitCommitInspectorService(tempRepo);

        String stackTrace = """
                java.lang.IllegalStateException: third party error
                	at org.apache.kafka.clients.NetworkClient.poll(NetworkClient.java:550)
                """;

        GitCommitInspectorService.GitInspectionResult result = inspector.inspect("nonexistent-service", stackTrace);

        assertNull(result.resolvedPath());
        assertFalse(result.hasCommittedHistory());
        assertTrue(result.commits().isEmpty());
        assertTrue(result.plainReport().contains("No tenant source file resolved under tenants/** for service: nonexistent-service"));

        assertFalse(result.plainReport().contains("Commit "));
        assertFalse(result.plainReport().contains("@alphafin.com"));
    }

    @Test
    void defaultRepoRootResolvesFromLocalWorkspace() {
        Path resolved = GitCommitInspectorService.resolveRepositoryRoot(null);
        assertNotNull(resolved);
        assertTrue(Files.exists(resolved.resolve("tenants")), "Should locate root containing tenants directory");
    }

    private static void runCommand(Path dir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("Command failed [" + String.join(" ", command) + "]: " + stderr);
        }
    }
}
