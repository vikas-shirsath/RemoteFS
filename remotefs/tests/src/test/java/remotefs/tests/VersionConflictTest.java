package remotefs.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import remotefs.common.FileOpException;
import remotefs.common.FileService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VersionConflictTest — verifies optimistic concurrency control.
 *
 * SCENARIO:
 *   1. Create a file at version 0 → becomes version 1.
 *   2. Two threads both read version 1, then simultaneously try to
 *      write with expectedVersion=1.
 *   3. Exactly ONE should succeed (getting version 2), and exactly ONE
 *      should fail with a "version conflict" error.
 *
 * WHY THIS WORKS (viva talking point):
 *   The write lock ensures only one writer enters the critical section
 *   at a time. The first writer passes the version check (expected=1,
 *   current=1) and increments to version 2. The second writer then
 *   enters and fails (expected=1, current=2). This is classic
 *   optimistic concurrency control — no data is lost.
 */
class VersionConflictTest {

    @TempDir
    static Path tempDir;

    static FileService service;
    static String token;

    @BeforeAll
    static void setUp() throws Exception {
        service = TestHelper.startServer(tempDir);
        token = TestHelper.loginDefaultUser(service);
    }

    @Test
    void twoWritersWithSameExpectedVersion_exactlyOneSucceeds() throws Exception {
        final String FILE = "version_conflict_test.txt";

        // --- Setup: create the file (version 0 → version 1) ---
        service.write(token, FILE,
                "initial content".getBytes(StandardCharsets.UTF_8), 0);

        // Both threads will try to write with expectedVersion=1
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger conflicts = new AtomicInteger(0);

        Future<?>[] futures = new Future[2];
        for (int i = 0; i < 2; i++) {
            final int threadId = i;
            futures[i] = executor.submit(() -> {
                startLatch.await();
                try {
                    // Both threads think the current version is 1
                    String content = "Content from thread " + threadId;
                    service.write(token, FILE,
                            content.getBytes(StandardCharsets.UTF_8), 1);
                    // If we get here, the write succeeded
                    successes.incrementAndGet();
                    System.out.println("  Thread " + threadId + ": write SUCCEEDED (got version 2)");
                } catch (FileOpException e) {
                    // Expected for the loser — version conflict
                    if (e.getMessage().contains("Version conflict")) {
                        conflicts.incrementAndGet();
                        System.out.println("  Thread " + threadId + ": version conflict (expected)");
                    } else {
                        throw new RuntimeException("Unexpected error: " + e.getMessage(), e);
                    }
                }
                return null;
            });
        }

        // Release both threads at the same time
        startLatch.countDown();

        // Wait for both to finish
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        // --- Assertions ---
        assertEquals(1, successes.get(),
                "Exactly one write should succeed");
        assertEquals(1, conflicts.get(),
                "Exactly one write should fail with version conflict");

        // The file should be at version 2 now
        var info = service.stat(token, FILE);
        assertEquals(2, info.getVersion(),
                "File should be at version 2 after one successful write");

        System.out.println("[VersionConflictTest] Optimistic concurrency control works ✓");
    }
}
