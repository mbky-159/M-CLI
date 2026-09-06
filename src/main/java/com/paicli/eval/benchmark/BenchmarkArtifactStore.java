package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Creates one path-safe benchmark run tree and persists its deterministic artifacts. */
public final class BenchmarkArtifactStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path runDirectory;

    private BenchmarkArtifactStore(Path runDirectory) {
        this.runDirectory = runDirectory;
    }

    public static BenchmarkArtifactStore create(Path outputRoot, String runId) throws IOException {
        if (outputRoot == null) {
            throw new IllegalArgumentException("outputRoot must not be null");
        }
        CaseDefinition.requireSafeIdentifier(runId, "run id");
        Path root = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path runDirectory = root.resolve(runId).normalize();
        if (!runDirectory.startsWith(root)) {
            throw new IllegalArgumentException("run id escapes outputRoot");
        }
        createNewSecureDirectory(runDirectory);
        return new BenchmarkArtifactStore(runDirectory);
    }

    public Path runDirectory() {
        return runDirectory;
    }

    public Path writeManifest(Object manifest) throws IOException {
        return writeRedactedJson(runDirectory.resolve("manifest.json"), manifest);
    }

    public synchronized EpisodeArtifacts episode(String caseId, String modelId, int repeat) throws IOException {
        CaseDefinition.requireSafeIdentifier(caseId, "case id");
        CaseDefinition.requireSafeIdentifier(modelId, "model id");
        if (repeat <= 0) {
            throw new IllegalArgumentException("repeat must be positive");
        }
        Path cases = ensureSecureDirectory(runDirectory.resolve("cases"));
        Path caseDirectory = ensureSecureDirectory(cases.resolve(caseId));
        Path models = ensureSecureDirectory(caseDirectory.resolve("models"));
        Path modelDirectory = ensureSecureDirectory(models.resolve(modelId));
        Path repeatDirectory = ensureSecureDirectory(modelDirectory.resolve("repeat-%03d".formatted(repeat)));
        return new EpisodeArtifacts(repeatDirectory);
    }

    public static final class EpisodeArtifacts {
        private final Path directory;

        private EpisodeArtifacts(Path directory) {
            this.directory = directory;
        }

        public Path directory() {
            return directory;
        }

        public Path writeRun(Object run) throws IOException {
            return writeRedactedJson(directory.resolve("run.json"), run);
        }

        public Path writeVerifier(Object verifier) throws IOException {
            return writeRedactedJson(directory.resolve("verifier.json"), verifier);
        }

        public Path writeAnswer(String answer) throws IOException {
            return writeSecure(directory.resolve("answer.md"), SecretRedactor.redact(text(answer)));
        }

        /** Writes the unredacted private trace; owner-only permissions are applied when supported. */
        public Path writeRawTrace(String rawJsonLines) throws IOException {
            return writeSecure(directory.resolve("raw.jsonl"), text(rawJsonLines));
        }
    }

    private static Path writeRedactedJson(Path target, Object value) throws IOException {
        String json = MAPPER.writeValueAsString(value);
        return writeSecure(target, SecretRedactor.redact(json) + System.lineSeparator());
    }

    private static Path writeSecure(Path target, String content) throws IOException {
        Path parent = ensureSecureDirectory(target.toAbsolutePath().normalize().getParent());
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(parent)) {
            throw new IllegalArgumentException("artifact target escapes its directory");
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("refusing to replace symbolic-link artifact: " + normalized);
        }

        Path temporary = Files.createTempFile(parent, ".artifact-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            setFilePermissionsBestEffort(temporary);
            try {
                Files.move(temporary, normalized,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            setFilePermissionsBestEffort(normalized);
            return normalized;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void createNewSecureDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException e) {
            throw new FileAlreadyExistsException("benchmark run already exists: " + directory);
        }
        setDirectoryPermissionsBestEffort(directory);
    }

    private static Path ensureSecureDirectory(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("artifact path is not a safe directory: " + normalized);
            }
        } else {
            Path parent = normalized.getParent();
            if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                ensureSecureDirectory(parent);
            }
            Files.createDirectory(normalized);
        }
        setDirectoryPermissionsBestEffort(normalized);
        return normalized;
    }

    private static void setDirectoryPermissionsBestEffort(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Non-POSIX file systems and restricted hosts retain their platform defaults.
        }
    }

    private static void setFilePermissionsBestEffort(Path file) {
        try {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Non-POSIX file systems and restricted hosts retain their platform defaults.
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
