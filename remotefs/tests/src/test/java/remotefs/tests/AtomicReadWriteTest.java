package remotefs.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import remotefs.common.FileService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AtomicReadWriteTest — verifies that reads during a write see only
 * the OLD or NEW content, never a partial mix.
 *
 * SCENARIO:
 *   1. Write a file containing "AAAA...A" (1000 bytes of 'A').
 *   2. Spawn a writer thread that overwrites it with "BBBB...B" (1000 bytes of 'B').
 *   3. Spawn multiple reader threads that repeatedly read the file.
 *   4. Every read must contain ONLY 'A's or ONLY 'B's — never a mix.
 *
 * WHY THIS WORKS (viva talking point):
 *   The write() method uses atomic file replacement:
 *     - New content is written to a temp file
 *     - The temp file is moved over the target with ATOMIC_MOVE
 *   This is an OS-level atomic pointer swap. At no point does the target
 *   file contain partial content. Combined with the read-write lock
 *   (readers wait for the writer to finish), reads always see a consistent state.
 */
class AtomicReadWriteTest {

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
    void readsNeverSeePartialContent() throws Exception {
        final String FILE = "atomic_test.txt";
        final int SIZE = 1000;

        // --- Initial content: all 'A's ---
        byte[] allAs = new byte[SIZE];
        java.util.Arrays.fill(allAs, (byte) 'A');
        service.write(token, FILE, allAs, 0);

        // Prepare replacement content: all 'B's
        byte[] allBs = new byte[SIZE];
        java.util.Arrays.fill(allBs, (byte) 'B');

        // Flag to track if any reader saw corrupted (mixed) content
        AtomicBoolean corruptionDetected = new AtomicBoolean(false);

        // Latch to synchronise start of all threads
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(6);

        // --- Writer thread: overwrites the file with all 'B's ---
        Future<Void> writer = executor.submit(() -> {
            startLatch.await();
            // Small sleep to let readers start competing
            Thread.sleep(10);
            service.write(token, FILE, allBs, 1); // v1 → v2
            return null;
        });

        // --- Reader threads: repeatedly read and check for consistency ---
        int readerCount = 5;
        int readsPerThread = 20;
        Future<?>[] readers = new Future[readerCount];

        for (int i = 0; i < readerCount; i++) {
            readers[i] = executor.submit(() -> {
                startLatch.await();
                for (int r = 0; r < readsPerThread; r++) {
                    try {
                        byte[] data = service.read(token, FILE);
                        // Check that content is homogeneous (all same byte)
                        byte firstByte = data[0];
                        for (byte b : data) {
                            if (b != firstByte) {
                                corruptionDetected.set(true);
                                System.err.println("CORRUPTION: mixed content detected! "
                                        + "First byte=" + (char) firstByte
                                        + ", found=" + (char) b);
                                return null;
                            }
                        }
                    } catch (Exception e) {
                        // Ignore transient lock timeouts — they're expected
                        // when the writer holds the exclusive lock
                    }
                }
                return null;
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();

        // Wait for completion
        writer.get();
        for (Future<?> reader : readers) {
            reader.get();
        }
        executor.shutdown();

        // --- Assertion: no corruption detected ---
        assertFalse(corruptionDetected.get(),
                "Readers saw mixed content (partial write). " +
                "Atomic write guarantee violated!");

        System.out.println("[AtomicReadWriteTest] No partial reads detected ✓");
    }
}
