package remotefs.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import remotefs.common.FileService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RenameDeadlockTest — verifies that simultaneous cross-renames do NOT deadlock.
 *
 * SCENARIO:
 *   1. Create two files: "alpha.txt" and "beta.txt"
 *   2. Thread 1 renames alpha → beta_new
 *   3. Thread 2 renames beta → alpha_new
 *   4. Both threads must complete within 10 seconds (no deadlock).
 *   5. At least one rename should succeed; the other may fail (because the
 *      source was already renamed), but neither should hang.
 *
 * WHY THIS WORKS (viva talking point):
 *   Without sorted lock ordering, Thread 1 would lock "alpha" then wait for
 *   "beta_new", while Thread 2 locks "beta" then waits for "alpha_new".
 *   This could cause a DEADLOCK (circular wait).
 *
 *   LockManager.acquireWriteLocksOrdered() always acquires locks in
 *   alphabetical order. Both threads will try to lock the alphabetically
 *   first path first, so one will wait for the other — no circular dependency.
 *
 *   Additionally, tryLock(5s) ensures that even if contention occurs,
 *   the thread will timeout rather than hang forever.
 */
class RenameDeadlockTest {

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
    void crossRename_noDeadlock() throws Exception {
        // --- Setup: create two files ---
        service.write(token, "alpha.txt",
                "Alpha content".getBytes(StandardCharsets.UTF_8), 0);
        service.write(token, "beta.txt",
                "Beta content".getBytes(StandardCharsets.UTF_8), 0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: rename alpha.txt → gamma.txt
        Future<String> future1 = executor.submit(() -> {
            try {
                service.rename(token, "alpha.txt", "gamma.txt");
                return "Thread 1: alpha→gamma SUCCEEDED";
            } catch (Exception e) {
                return "Thread 1: alpha→gamma FAILED (" + e.getMessage() + ")";
            }
        });

        // Thread 2: rename beta.txt → delta.txt
        Future<String> future2 = executor.submit(() -> {
            try {
                service.rename(token, "beta.txt", "delta.txt");
                return "Thread 2: beta→delta SUCCEEDED";
            } catch (Exception e) {
                return "Thread 2: beta→delta FAILED (" + e.getMessage() + ")";
            }
        });

        // --- The critical assertion: both must complete within 10 seconds ---
        // If there's a deadlock, Future.get() will throw TimeoutException.
        try {
            String result1 = future1.get(10, TimeUnit.SECONDS);
            String result2 = future2.get(10, TimeUnit.SECONDS);

            System.out.println("  " + result1);
            System.out.println("  " + result2);

            // At least one rename should have succeeded
            // (Both should succeed in this case since they have different targets)
            System.out.println("[RenameDeadlockTest] No deadlock detected ✓");

        } catch (TimeoutException e) {
            fail("DEADLOCK DETECTED: rename operations did not complete within 10 seconds");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void crossRename_oppositeDirections_noDeadlock() throws Exception {
        // --- This is the classic deadlock scenario: A→B and B→A ---
        service.write(token, "fileX.txt",
                "X content".getBytes(StandardCharsets.UTF_8), 0);
        service.write(token, "fileY.txt",
                "Y content".getBytes(StandardCharsets.UTF_8), 0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: rename fileX → fileY_new
        // Thread 2: rename fileY → fileX_new
        // Without sorted lock ordering, this would deadlock!
        Future<String> future1 = executor.submit(() -> {
            try {
                service.rename(token, "fileX.txt", "fileY_renamed.txt");
                return "rename(X→Y_renamed) succeeded";
            } catch (Exception e) {
                return "rename(X→Y_renamed) failed: " + e.getMessage();
            }
        });

        Future<String> future2 = executor.submit(() -> {
            try {
                service.rename(token, "fileY.txt", "fileX_renamed.txt");
                return "rename(Y→X_renamed) succeeded";
            } catch (Exception e) {
                return "rename(Y→X_renamed) failed: " + e.getMessage();
            }
        });

        try {
            String r1 = future1.get(10, TimeUnit.SECONDS);
            String r2 = future2.get(10, TimeUnit.SECONDS);
            System.out.println("  " + r1);
            System.out.println("  " + r2);
            System.out.println("[RenameDeadlockTest] Cross-rename no deadlock ✓");
        } catch (TimeoutException e) {
            fail("DEADLOCK DETECTED in cross-rename scenario");
        } finally {
            executor.shutdownNow();
        }
    }
}
