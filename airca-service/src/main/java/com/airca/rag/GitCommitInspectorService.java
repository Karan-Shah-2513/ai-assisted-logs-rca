package com.airca.rag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GitCommitInspectorService {

    private static final Logger log = LoggerFactory.getLogger(GitCommitInspectorService.class);

    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile(
            "(?:at\\s+)?([a-zA-Z_$][a-zA-Z0-9_$.]*)\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(([a-zA-Z0-9_$.-]+\\.java)(?::(\\d+))?\\)");

    private static final int DEFAULT_MAX_COMMITS = 5;

    private final String configuredRepoRoot;
    private final long timeoutSeconds;
    private final int maxOutputBytes;
    private final Path overrideRepoRoot;

    @Autowired
    public GitCommitInspectorService(
            @Value("${airca.repository.root:${AIRCA_REPOSITORY_ROOT:}}") String configuredRepoRoot,
            @Value("${airca.git.timeout-seconds:5}") long timeoutSeconds,
            @Value("${airca.git.max-output-bytes:262144}") int maxOutputBytes) {
        this.configuredRepoRoot = configuredRepoRoot;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
        this.overrideRepoRoot = null;
    }

    public GitCommitInspectorService(Path overrideRepoRoot) {
        this(overrideRepoRoot, 5, 262144);
    }

    public GitCommitInspectorService(Path overrideRepoRoot, long timeoutSeconds, int maxOutputBytes) {
        this.configuredRepoRoot = null;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
        this.overrideRepoRoot = overrideRepoRoot;
    }

    public Path getRepositoryRoot() {
        if (overrideRepoRoot != null) {
            return overrideRepoRoot.toAbsolutePath().normalize();
        }
        return resolveRepositoryRoot(configuredRepoRoot);
    }

    public static Path resolveRepositoryRoot(String configuredRoot) {
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            Path path = Path.of(configuredRoot.trim()).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                return path;
            }
        }

        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("tenants")) && Files.exists(dir.resolve(".git"))) {
                return dir;
            }
        }
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("tenants"))) {
                return dir;
            }
        }
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                return dir;
            }
        }
        return cwd;
    }

    public String findRelevantDiff(String serviceName, String stackTrace) {
        GitInspectionResult result = inspect(serviceName, stackTrace);
        return result.plainReport();
    }

    public GitInspectionResult inspect(String serviceName, String stackTrace) {
        String resolvedPath;
        try {
            resolvedPath = resolveSafeTenantSourcePath(serviceName, stackTrace);
        } catch (Exception e) {
            log.warn("Failed resolving safe tenant source path: {}", e.getMessage());
            return new GitInspectionResult(
                    null,
                    false,
                    List.of(),
                    null,
                    "Error resolving tenant source path: " + e.getMessage());
        }

        if (resolvedPath == null) {
            String report = "No tenant source file resolved under tenants/** for service: "
                    + (serviceName == null || serviceName.isBlank() ? "unknown" : serviceName);
            return new GitInspectionResult(null, false, List.of(), null, report);
        }

        List<GitCommitInfo> commits;
        try {
            commits = extractCommittedHistory(resolvedPath, DEFAULT_MAX_COMMITS);
        } catch (Exception e) {
            log.warn("Failed extracting git commit history for {}: {}", resolvedPath, e.getMessage());
            return new GitInspectionResult(
                    resolvedPath,
                    false,
                    List.of(),
                    null,
                    "Git inspection error on path " + resolvedPath + ": " + e.getMessage());
        }

        if (!commits.isEmpty()) {
            StringBuilder report = new StringBuilder();
            report.append("Source: ").append(resolvedPath)
                    .append(" (").append(commits.size()).append(" commit")
                    .append(commits.size() > 1 ? "s" : "").append(" found)\n\n");

            for (int i = 0; i < commits.size(); i++) {
                if (i > 0) {
                    report.append("\n----------------------------------------\n\n");
                }
                GitCommitInfo commit = commits.get(i);
                report.append("Commit ").append(commit.commitHash()).append("\n")
                        .append("Author: ").append(commit.author()).append("\n")
                        .append("Date:   ").append(commit.timestamp()).append("\n")
                        .append("Summary: ").append(commit.message()).append("\n\n")
                        .append(commit.diff().isBlank() ? "(No file-level diff in commit)" : commit.diff());
            }
            return new GitInspectionResult(resolvedPath, true, commits, null, report.toString());
        }

        String workingTreeDiff;
        try {
            workingTreeDiff = extractWorkingTreeDiff(resolvedPath);
        } catch (Exception e) {
            log.warn("Failed extracting working tree diff for {}: {}", resolvedPath, e.getMessage());
            workingTreeDiff = "Error extracting working tree diff: " + e.getMessage();
        }

        StringBuilder report = new StringBuilder();
        report.append("Source: ").append(resolvedPath).append("\n")
                .append("No committed git history found for this path.\n")
                .append("Working tree diff:\n");

        if (workingTreeDiff != null && !workingTreeDiff.isBlank()) {
            report.append(workingTreeDiff);
        } else {
            report.append("No uncommitted working tree changes detected.");
        }

        return new GitInspectionResult(resolvedPath, false, List.of(), workingTreeDiff, report.toString());
    }

    public String resolveSafeTenantSourcePath(String serviceName, String stackTrace) {
        Path repoRoot = getRepositoryRoot();
        Path tenantsDir = repoRoot.resolve("tenants").toAbsolutePath().normalize();
        if (!Files.isDirectory(tenantsDir)) {
            return null;
        }

        List<Path> allTenantFiles = listTenantFiles(tenantsDir);
        if (allTenantFiles.isEmpty()) {
            return null;
        }

        List<StackFrame> frames = parseStackFrames(stackTrace);
        for (StackFrame frame : frames) {
            if (isUnsafeIdentifier(frame.className()) || isUnsafeIdentifier(frame.fileName())) {
                continue;
            }
            if (!frame.fileName().endsWith(".java")) {
                continue;
            }

            String topClass = frame.className().contains("$")
                    ? frame.className().substring(0, frame.className().indexOf('$'))
                    : frame.className();
            String pkg = topClass.contains(".")
                    ? topClass.substring(0, topClass.lastIndexOf('.'))
                    : "";
            String pkgPath = pkg.isEmpty() ? "" : pkg.replace('.', '/');

            Path matchedFile = findBestMatchingFile(allTenantFiles, repoRoot, tenantsDir, serviceName, frame.fileName(), pkgPath);
            if (matchedFile != null) {
                return toSafeRelativePath(repoRoot, tenantsDir, matchedFile);
            }
        }

        if (serviceName != null && !serviceName.isBlank()) {
            String normalizedService = serviceName.trim().toLowerCase(Locale.ROOT);
            for (Path file : allTenantFiles) {
                String rel = toSafeRelativePath(repoRoot, tenantsDir, file);
                if (rel == null) {
                    continue;
                }
                String lowerRel = rel.toLowerCase(Locale.ROOT);
                if (lowerRel.contains("/" + normalizedService + "/") && rel.endsWith(".java")) {
                    if (rel.endsWith("Controller.java") || rel.endsWith("Application.java") || rel.endsWith("Service.java")) {
                        return rel;
                    }
                }
            }
        }

        return null;
    }

    public List<GitCommitInfo> extractCommittedHistory(String relativePath, int maxCommits) {
        if (relativePath == null || relativePath.isBlank()) {
            return Collections.emptyList();
        }
        int limit = maxCommits <= 0 ? DEFAULT_MAX_COMMITS : maxCommits;

        ProcessResult result = runGitCommand(List.of(
                "log",
                "-n", String.valueOf(limit),
                "--date=iso-strict",
                "--pretty=format:COMMIT_START%n%H%n%an <%ae>%n%ad%n%s%nDIFF_START",
                "-p",
                "--",
                relativePath));

        if (result.exitCode() != 0) {
            String stderr = result.stderr();
            if (stderr.contains("does not have any commits yet") || stderr.contains("unknown revision")) {
                return Collections.emptyList();
            }
            throw new GitCommandException("git log exited with code " + result.exitCode() + ": " + stderr);
        }

        String stdout = result.stdout().trim();
        if (!stdout.contains("COMMIT_START")) {
            return Collections.emptyList();
        }

        String[] blocks = stdout.split("(?:^|\\r?\\n)COMMIT_START\\r?\\n");
        List<GitCommitInfo> commits = new ArrayList<>();
        for (String block : blocks) {
            if (block.isBlank()) {
                continue;
            }
            int diffStartIdx = block.indexOf("DIFF_START");
            String metaSection = diffStartIdx != -1 ? block.substring(0, diffStartIdx).trim() : block.trim();
            String diffSection = diffStartIdx != -1 ? block.substring(diffStartIdx + "DIFF_START".length()).trim() : "";

            String[] metaLines = metaSection.split("\\r?\\n");
            String hash = metaLines.length > 0 ? metaLines[0].trim() : "";
            String author = metaLines.length > 1 ? metaLines[1].trim() : "";
            String timestamp = metaLines.length > 2 ? metaLines[2].trim() : "";
            String message = metaLines.length > 3 ? metaLines[3].trim() : "";

            commits.add(new GitCommitInfo(hash, author, timestamp, message, diffSection));
            if (commits.size() >= limit) {
                break;
            }
        }
        return commits;
    }

    public String extractWorkingTreeDiff(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }

        ProcessResult lsResult = runGitCommand(List.of("ls-files", "--error-unmatch", "--", relativePath));
        if (lsResult.exitCode() == 0) {
            ProcessResult diffHeadResult = runGitCommand(List.of("diff", "HEAD", "--", relativePath));
            if (diffHeadResult.exitCode() == 0 || diffHeadResult.exitCode() == 1) {
                String diff = diffHeadResult.stdout().trim();
                if (!diff.isBlank()) {
                    return diff;
                }
            }

            ProcessResult wtDiffResult = runGitCommand(List.of("diff", "--", relativePath));
            if (wtDiffResult.exitCode() == 0 || wtDiffResult.exitCode() == 1) {
                String diff = wtDiffResult.stdout().trim();
                if (!diff.isBlank()) {
                    return diff;
                }
            }
            return "";
        }

        ProcessResult untrackedDiffResult = runGitCommand(List.of(
                "diff",
                "--no-index",
                "--",
                "/dev/null",
                relativePath));

        if (untrackedDiffResult.exitCode() == 0 || untrackedDiffResult.exitCode() == 1) {
            return untrackedDiffResult.stdout().trim();
        }
        return "";
    }

    private ProcessResult runGitCommand(List<String> args) {
        Path repoRoot = getRepositoryRoot();
        if (!Files.exists(repoRoot)) {
            throw new GitCommandException("Repository root directory does not exist: " + repoRoot);
        }

        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("safe.directory=*");
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoRoot.toFile());

        try {
            Process process = pb.start();

            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readBoundedStream(process.getInputStream(), maxOutputBytes));
            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readBoundedStream(process.getErrorStream(), maxOutputBytes));

            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new GitCommandException(
                        "Git command timed out after " + timeoutSeconds + "s: " + String.join(" ", command));
            }

            byte[] stdoutBytes = stdoutFuture.get(1, TimeUnit.SECONDS);
            byte[] stderrBytes = stderrFuture.get(1, TimeUnit.SECONDS);

            int exitCode = process.exitValue();
            String stdout = new String(stdoutBytes, StandardCharsets.UTF_8);
            String stderr = new String(stderrBytes, StandardCharsets.UTF_8);

            return new ProcessResult(exitCode, stdout, stderr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandException("Git command interrupted: " + String.join(" ", command), e);
        } catch (GitCommandException e) {
            throw e;
        } catch (Exception e) {
            throw new GitCommandException(
                    "Failed to execute git command (" + String.join(" ", command) + "): " + e.getMessage(), e);
        }
    }

    private static byte[] readBoundedStream(InputStream in, int maxBytes) {
        try (in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int nRead;
            int totalRead = 0;
            while ((nRead = in.read(data, 0, Math.min(data.length, Math.max(0, maxBytes - totalRead)))) != -1) {
                buffer.write(data, 0, nRead);
                totalRead += nRead;
                if (totalRead >= maxBytes) {
                    break;
                }
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static List<Path> listTenantFiles(Path tenantsDir) {
        try (Stream<Path> stream = Files.walk(tenantsDir, 15)) {
            return stream
                    .filter(Files::isRegularFile)
                    .toList();
        } catch (IOException e) {
            log.warn("Failed scanning tenants directory: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static Path findBestMatchingFile(
            List<Path> tenantFiles,
            Path repoRoot,
            Path tenantsDir,
            String serviceName,
            String fileName,
            String packagePath) {

        String normalizedService = serviceName == null ? "" : serviceName.trim().toLowerCase(Locale.ROOT);
        String targetPkgFile = (packagePath == null || packagePath.isBlank()) ? fileName : (packagePath + "/" + fileName);

        record ScoredFile(Path path, int score) {}

        List<ScoredFile> scored = new ArrayList<>();
        for (Path file : tenantFiles) {
            String rel = toSafeRelativePath(repoRoot, tenantsDir, file);
            if (rel == null || !rel.endsWith("/" + fileName)) {
                continue;
            }

            int score = 0;
            boolean matchesPackage = !packagePath.isBlank() && rel.endsWith("/" + targetPkgFile);
            boolean matchesService = !normalizedService.isBlank()
                    && rel.toLowerCase(Locale.ROOT).contains("/" + normalizedService + "/");
            boolean isMain = rel.contains("/src/main/");

            if (matchesPackage && matchesService) {
                score += 100;
            } else if (matchesPackage) {
                score += 50;
            } else if (matchesService) {
                score += 30;
            } else {
                score += 10;
            }

            if (isMain) {
                score += 5;
            }

            scored.add(new ScoredFile(file, score));
        }

        return scored.stream()
                .max(Comparator.comparingInt(ScoredFile::score))
                .map(ScoredFile::path)
                .orElse(null);
    }

    private static String toSafeRelativePath(Path repoRoot, Path tenantsDir, Path file) {
        if (file == null) {
            return null;
        }
        Path normFile = file.toAbsolutePath().normalize();
        if (!normFile.startsWith(tenantsDir)) {
            return null;
        }
        String rel = repoRoot.relativize(normFile).toString().replace('\\', '/');
        if (!rel.startsWith("tenants/")) {
            return null;
        }
        return rel;
    }

    private static boolean isUnsafeIdentifier(String str) {
        if (str == null || str.isBlank()) {
            return true;
        }
        return str.contains("..") || str.contains("/") || str.contains("\\") || str.contains("\0");
    }

    private static List<StackFrame> parseStackFrames(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return Collections.emptyList();
        }
        List<StackFrame> frames = new ArrayList<>();
        Matcher matcher = STACK_FRAME_PATTERN.matcher(stackTrace);
        while (matcher.find()) {
            String className = matcher.group(1);
            String methodName = matcher.group(2);
            String fileName = matcher.group(3);
            int lineNumber = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : -1;
            frames.add(new StackFrame(className, methodName, fileName, lineNumber));
        }
        return frames;
    }

    public record GitCommitInfo(
            String commitHash,
            String author,
            String timestamp,
            String message,
            String diff) {
    }

    public record GitInspectionResult(
            String resolvedPath,
            boolean hasCommittedHistory,
            List<GitCommitInfo> commits,
            String workingTreeDiff,
            String plainReport) {
    }

    private record StackFrame(
            String className,
            String methodName,
            String fileName,
            int lineNumber) {
    }

    private record ProcessResult(
            int exitCode,
            String stdout,
            String stderr) {
    }

    public static class GitCommandException extends RuntimeException {
        public GitCommandException(String message) {
            super(message);
        }

        public GitCommandException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
