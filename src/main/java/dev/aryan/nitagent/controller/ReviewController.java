package dev.aryan.nitagent.controller;

import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.util.zip.*;
import dev.aryan.nitagent.agent.review.ReviewAgentService;
import dev.aryan.nitagent.agent.test.TestGenAgentService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.Executor;

@RestController
public class ReviewController {

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".py", ".ts", ".tsx", ".js", ".jsx", ".go", ".rs", ".cs",
            ".cpp", ".c", ".h", ".rb", ".kt", ".swift", ".scala", ".php"
    );
    private static final long MAX_UNCOMPRESSED_ZIP_BYTES = 250L * 1024L * 1024L;
    private static final long MAX_ZIP_ENTRY_BYTES = 25L * 1024L * 1024L;
    private static final int MAX_ZIP_FILES = 10_000;
    private static final int MAX_ZIP_DEPTH = 40;
    private final ReviewAgentService reviewAgentService;
    private final TestGenAgentService testGenAgentService;
    private final Executor nitTaskExecutor;

    private static final Set<String> IGNORED_ZIP_PARTS = Set.of(
            ".git",
            ".idea",
            ".vscode",
            ".venv",
            "venv",
            "env",
            "node_modules",
            "__pycache__",
            ".pytest_cache",
            ".mypy_cache",
            ".ruff_cache",
            "target",
            "build",
            "dist",
            ".next",
            ".gradle",
            "coverage"
    );

    public ReviewController(ReviewAgentService reviewAgentService, TestGenAgentService testGenAgentService, @Qualifier("nitTaskExecutor") Executor nitTaskExecutor) {
        this.reviewAgentService = reviewAgentService;
        this.testGenAgentService = testGenAgentService;
        this.nitTaskExecutor = nitTaskExecutor;
    }

    @PostMapping(value = "/review", consumes = "multipart/form-data")
    public SseEmitter review(@RequestParam("zip") MultipartFile zipFile, @RequestParam(defaultValue = "thinking") String modelMode) {
        SseEmitter emitter = new SseEmitter(0L);
        nitTaskExecutor.execute(() -> {
            Path tempDir = null;
            Path deleteRoot = null;
            try {
                tempDir = extractZip(zipFile);
                deleteRoot = findDeleteRoot(tempDir);
                boolean fast = modelMode.equalsIgnoreCase("fast");
                reviewAgentService.review(tempDir.toString(), emitter, fast);
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, e);
            } finally {
                if (deleteRoot != null) deleteDir(deleteRoot);
            }
        });
        return emitter;
    }

    @PostMapping(value = "/list-files", consumes = "multipart/form-data")
    public ResponseEntity<List<String>> listFiles(@RequestParam("zip") MultipartFile zipFile) {
        try {
            List<String> files = new ArrayList<>();
            int fileCount = 0;
            long totalBytes = 0;
            byte[] buffer = new byte[8192];
            try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    validateZipEntryName(entry.getName());
                    if (!entry.isDirectory()) {
                        fileCount++;
                        if (fileCount > MAX_ZIP_FILES) throw new IOException("Zip contains too many files.");
                        long entryBytes = 0;
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            entryBytes += read;
                            totalBytes += read;
                            if (entryBytes > MAX_ZIP_ENTRY_BYTES) throw new IOException("Zip entry exceeds maximum size: " + entry.getName());
                            if (totalBytes > MAX_UNCOMPRESSED_ZIP_BYTES) throw new IOException("Zip exceeds maximum uncompressed size.");
                        }
                        String name = entry.getName();
                        String lower = name.toLowerCase();
                        boolean isSource = SOURCE_EXTENSIONS.stream().anyMatch(lower::endsWith);
                        if (isSource) files.add(name);
                    }
                    zis.closeEntry();
                }
            }
            files.sort(String::compareTo);
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/generate-tests")
    public SseEmitter generateTests(@RequestBody String path, @RequestParam(defaultValue = "false") boolean isRepo, @RequestParam(defaultValue = "thinking") String modelMode) {
        SseEmitter emitter = new SseEmitter(0L);
        nitTaskExecutor.execute(() -> {
            try {
                boolean fast = modelMode.equalsIgnoreCase("fast");
                testGenAgentService.generateTests(path, isRepo, emitter, fast);
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, e);
            }
        });
        return emitter;
    }

    private Path extractZip(MultipartFile zipFile) throws IOException {
        Path tempDir = Files.createTempDirectory("nit-" + UUID.randomUUID());
        int fileCount = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[8192];

        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                validateZipEntryName(entry.getName());

                if (shouldSkipZipEntry(entry.getName())) {
                    zis.closeEntry();
                    continue;
                }
                Path target = tempDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(tempDir)) {
                    throw new IOException("Zip entry escapes target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    fileCount++;
                    if (fileCount > MAX_ZIP_FILES) {
                        throw new IOException("Zip contains too many files.");
                    }
                    Files.createDirectories(target.getParent());
                    long entryBytes = 0;
                    try (OutputStream out = Files.newOutputStream(
                            target,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING
                    )) {
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            entryBytes += read;
                            totalBytes += read;
                            if (entryBytes > MAX_ZIP_ENTRY_BYTES) {
                                throw new IOException("Zip entry exceeds maximum size: " + entry.getName());
                            }
                            if (totalBytes > MAX_UNCOMPRESSED_ZIP_BYTES) {
                                throw new IOException("Zip exceeds maximum uncompressed size.");
                            }
                            out.write(buffer, 0, read);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        return unwrapSingleTopLevelDir(tempDir);
    }

    private void validateZipEntryName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Zip entry has empty name.");
        if (name.contains("\0")) throw new IOException("Zip entry contains invalid null byte.");
        if (Path.of(name).isAbsolute()) throw new IOException("Zip entry uses absolute path: " + name);
        int depth = name.split("/|\\\\").length;
        if (depth > MAX_ZIP_DEPTH) throw new IOException("Zip entry path is too deep: " + name);
    }

    private Path unwrapSingleTopLevelDir(Path dir) throws IOException {
        Path current = dir;
        while (true) {
            try (var stream = Files.list(current)) {
                var entries = stream.toList();
                if (entries.size() == 1 && Files.isDirectory(entries.get(0))) current = entries.get(0);
                else return current;
            }
        }
    }

    private Path findDeleteRoot(Path extractedPath) {
        Path current = extractedPath;
        while (current.getParent() != null && current.getParent().getFileName() != null && current.getParent().getFileName().toString().startsWith("nit-")) current = current.getParent();
        return current;
    }

    private void sendError(SseEmitter emitter, Exception e) {
        try {
            emitter.send("[error] " + e.getMessage() + "\n");
        } catch (IOException ignored) {}
        emitter.complete();
    }

    private void deleteDir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {}
    }

    private boolean shouldSkipZipEntry(String name) {
        String normalized = name.replace('\\', '/');
        for (String part : normalized.split("/")) {
            if (IGNORED_ZIP_PARTS.contains(part)) return true;
        }
        return false;
    }
}