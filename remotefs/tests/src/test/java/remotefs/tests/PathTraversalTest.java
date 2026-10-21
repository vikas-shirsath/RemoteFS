package remotefs.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import remotefs.common.FileOpException;
import remotefs.common.FileService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PathTraversalTest — verifies that malicious paths are rejected.
 *
 * SCENARIO:
 *   Clients attempt to access files outside the storage root using
 *   directory traversal ("../../../etc/passwd"), absolute paths
 *   ("/etc/shadow"), or other path manipulation tricks.
 *
 * EXPECTED RESULT:
 *   Every malicious path must be rejected with a FileOpException.
 *   No file outside the storage root should ever be accessible.
 *
 * WHY THIS MATTERS (viva talking point):
 *   Path traversal is one of the OWASP Top 10 vulnerabilities. Without
 *   PathValidator, a client could read/write any file the server process
 *   has access to — including system files, source code, or other users'
 *   data. Our PathValidator normalises every path and checks that the
 *   result starts with the storage root.
 */
class PathTraversalTest {

    @TempDir
    static Path tempDir;

    static FileService service;
    static String token;

    @BeforeAll
    static void setUp() throws Exception {
        service = TestHelper.startServer(tempDir);
        token = TestHelper.loginDefaultUser(service);
    }

    /**
     * Tests a variety of path traversal attack strings.
     * Each one must be rejected with FileOpException.
     */
    @ParameterizedTest(name = "malicious path: \"{0}\"")
    @ValueSource(strings = {
            // Basic traversal
            "../secret.txt",
            "../../etc/passwd",
            "../../../etc/shadow",
            // Double-traversal in subdir
            "subdir/../../etc/passwd",
            "a/b/c/../../../../etc/hosts",
            // Backslash variants (Windows)
            "..\\secret.txt",
            "..\\..\\windows\\system32\\config",
            // Absolute paths
            "/etc/passwd",
            "/tmp/evil.txt",
    })
    void pathTraversal_shouldBeRejected(String maliciousPath) {
        FileOpException ex = assertThrows(FileOpException.class, () -> {
            service.read(token, maliciousPath);
        });

        // The error message should indicate access denial, not leak internal paths
        System.out.println("  ✓ Rejected: \"" + maliciousPath + "\" → " + ex.getMessage());
    }

    /**
     * Tests that absolute Windows-style paths are rejected.
     */
    @Test
    void absoluteWindowsPath_shouldBeRejected() {
        assertThrows(FileOpException.class, () -> {
            service.read(token, "C:\\Windows\\System32\\config\\SAM");
        });
    }

    /**
     * Tests that valid paths within the storage root ARE accepted.
     */
    @Test
    void validPaths_shouldBeAccepted() throws Exception {
        // Create a file and read it back — should work fine
        service.write(token, "safe_file.txt",
                "safe content".getBytes(StandardCharsets.UTF_8), 0);

        byte[] data = service.read(token, "safe_file.txt");
        assertEquals("safe content", new String(data, StandardCharsets.UTF_8));

        // Subdirectory access should also work
        service.mkdir(token, "subdir");
        service.write(token, "subdir/nested.txt",
                "nested content".getBytes(StandardCharsets.UTF_8), 0);

        data = service.read(token, "subdir/nested.txt");
        assertEquals("nested content", new String(data, StandardCharsets.UTF_8));
    }

    /**
     * Tests that a path which normalises to exactly the storage root
     * does not escape it (edge case: "subdir/..").
     */
    @Test
    void pathNormalizesToRoot_shouldBeAccepted() throws Exception {
        // "subdir/.." normalises to the storage root itself — should be valid
        // (it's a directory listing, not an escape)
        assertDoesNotThrow(() -> {
            service.list(token, "subdir/..");
        });
    }
}
