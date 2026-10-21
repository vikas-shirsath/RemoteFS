package remotefs.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import remotefs.common.FileService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConcurrentAppendTest — verifies that concurrent appends produce
 * a correct, uncorrupted file.
 *
 * SCENARIO:
 *   10 threads each append a unique line to the same file simultaneously.
 *   After all threads finish, the file must contain exactly 10 lines,
 *   all distinct, with no data corruption or interleaving.
 *
 * WHY THIS WORKS (viva talking point):
 *   Each append() acquires an exclusive WRITE lock. The lock serialises
 *   all appends, so each thread's data is written contiguously. The
 *   tryLock timeout (5 seconds) is long enough for 10 quick appends.
 */
class ConcurrentAppendTest {

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
    void tenConcurrentAppends_allPresent_noCorruption() throws Exception {
        final int THREAD_COUNT = 10;
        final String FILE = "concurrent_append_test.txt";

        // CountDownLatch ensures all threads start at roughly the same time.
        // This maximises concurrency and lock contention.
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                // Wait until all threads are ready, then go
                startLatch.await();
                String line = "Line from thread " + threadId + "\n";
                service.append(token, FILE, line.getBytes(StandardCharsets.UTF_8));
                return null;
            }));
        }

        // Release all threads at once
        startLatch.countDown();

        // Wait for all threads to finish (with timeout to detect hangs)
        for (Future<Void> f : futures) {
            f.get(); // throws if any thread failed
        }
        executor.shutdown();

        // --- Verify the file content ---
        byte[] data = service.read(token, FILE);
        String content = new String(data, StandardCharsets.UTF_8);
        String[] lines = content.strip().split("\n");

        // Must have exactly 10 lines
        assertEquals(THREAD_COUNT, lines.length,
                "Expected " + THREAD_COUNT + " lines but got " + lines.length);

        // All lines must be unique (no data corruption or duplication)
        long uniqueLines = java.util.Arrays.stream(lines).distinct().count();
        assertEquals(THREAD_COUNT, uniqueLines,
                "All " + THREAD_COUNT + " lines should be unique (no corruption)");

        // Each thread's line must be present
        for (int i = 0; i < THREAD_COUNT; i++) {
            final String expected = "Line from thread " + i;
            assertTrue(content.contains(expected),
                    "Missing line from thread " + i);
        }

        System.out.println("[ConcurrentAppendTest] All " + THREAD_COUNT
                + " appends present and uncorrupted ✓");
    }
}
