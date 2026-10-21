package remotefs.tests;

import remotefs.common.FileInfo;
import remotefs.common.FileOpException;
import remotefs.common.FileService;

import java.nio.charset.StandardCharsets;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;

/**
 * End-to-end smoke test that exercises every client command against
 * a running server. Run this AFTER starting the server on port 1099.
 */
public class E2ETest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== RemoteFS End-to-End Test ===\n");

        // Connect to the running server
        Registry registry = LocateRegistry.getRegistry("localhost", 1099);
        FileService svc = (FileService) registry.lookup("FileService");
        System.out.println("✓ Connected to server\n");

        int passed = 0;
        int failed = 0;

        // --- 1. LOGIN (wrong password) ---
        try {
            svc.login("admin", "wrongpass");
            System.out.println("✗ [1] login with wrong password should have failed");
            failed++;
        } catch (FileOpException e) {
            System.out.println("✓ [1]  login (wrong password rejected): " + e.getMessage());
            passed++;
        }

        // --- 2. LOGIN (correct) ---
        String token = svc.login("admin", "admin123");
        System.out.println("✓ [2]  login admin/admin123 → token=" + token.substring(0, 8) + "...");
        passed++;

        // --- 3. MKDIR ---
        svc.mkdir(token, "testdir");
        System.out.println("✓ [3]  mkdir testdir");
        passed++;

        // --- 4. WRITE (new file) ---
        int v1 = svc.write(token, "testdir/hello.txt",
                "Hello, RemoteFS!\n".getBytes(StandardCharsets.UTF_8), 0);
        System.out.println("✓ [4]  write testdir/hello.txt → v" + v1);
        passed++;

        // --- 5. READ ---
        byte[] data = svc.read(token, "testdir/hello.txt");
        String content = new String(data, StandardCharsets.UTF_8);
        if (content.equals("Hello, RemoteFS!\n")) {
            System.out.println("✓ [5]  read testdir/hello.txt → correct content");
            passed++;
        } else {
            System.out.println("✗ [5] read returned wrong content: " + content);
            failed++;
        }

        // --- 6. STAT ---
        FileInfo info = svc.stat(token, "testdir/hello.txt");
        if (info.getVersion() == 1 && !info.isDirectory() && info.getSize() > 0) {
            System.out.println("✓ [6]  stat → name=" + info.getName()
                    + ", size=" + info.getSize() + ", v" + info.getVersion());
            passed++;
        } else {
            System.out.println("✗ [6] stat returned wrong info: " + info);
            failed++;
        }

        // --- 7. WRITE (overwrite with version check) ---
        int v2 = svc.write(token, "testdir/hello.txt",
                "Updated content\n".getBytes(StandardCharsets.UTF_8), v1);
        System.out.println("✓ [7]  write (overwrite v" + v1 + " → v" + v2 + ")");
        passed++;

        // --- 8. WRITE (version conflict) ---
        try {
            svc.write(token, "testdir/hello.txt",
                    "stale write".getBytes(StandardCharsets.UTF_8), v1); // stale version
            System.out.println("✗ [8] version conflict should have been thrown");
            failed++;
        } catch (FileOpException e) {
            System.out.println("✓ [8]  write version conflict detected: " + e.getMessage());
            passed++;
        }

        // --- 9. APPEND ---
        svc.append(token, "testdir/hello.txt",
                "Appended line\n".getBytes(StandardCharsets.UTF_8));
        data = svc.read(token, "testdir/hello.txt");
        content = new String(data, StandardCharsets.UTF_8);
        if (content.contains("Updated content") && content.contains("Appended line")) {
            System.out.println("✓ [9]  append → content has both original + appended");
            passed++;
        } else {
            System.out.println("✗ [9] append failed, content: " + content);
            failed++;
        }

        // --- 10. LIST ---
        List<FileInfo> entries = svc.list(token, "testdir");
        if (entries.size() >= 1) {
            System.out.println("✓ [10] ls testdir → " + entries.size() + " item(s)");
            for (FileInfo fi : entries) {
                System.out.println("       " + fi);
            }
            passed++;
        } else {
            System.out.println("✗ [10] ls testdir returned empty");
            failed++;
        }

        // --- 11. RENAME ---
        svc.rename(token, "testdir/hello.txt", "testdir/renamed.txt");
        FileInfo renamedInfo = svc.stat(token, "testdir/renamed.txt");
        System.out.println("✓ [11] mv hello.txt → renamed.txt (v" + renamedInfo.getVersion() + ")");
        passed++;

        // --- 12. Old path should not exist ---
        try {
            svc.stat(token, "testdir/hello.txt");
            System.out.println("✗ [12] old path should not exist after rename");
            failed++;
        } catch (FileOpException e) {
            System.out.println("✓ [12] old path correctly not found after rename");
            passed++;
        }

        // --- 13. DELETE ---
        svc.delete(token, "testdir/renamed.txt");
        System.out.println("✓ [13] rm testdir/renamed.txt");
        passed++;

        // --- 14. DELETE directory ---
        svc.delete(token, "testdir");
        System.out.println("✓ [14] rm testdir (empty dir)");
        passed++;

        // --- 15. PATH TRAVERSAL ---
        try {
            svc.read(token, "../../etc/passwd");
            System.out.println("✗ [15] path traversal should have been blocked");
            failed++;
        } catch (FileOpException e) {
            System.out.println("✓ [15] path traversal blocked: " + e.getMessage());
            passed++;
        }

        // --- 16. INVALID TOKEN ---
        try {
            svc.list("fake-token-12345", "");
            System.out.println("✗ [16] invalid token should have been rejected");
            failed++;
        } catch (FileOpException e) {
            System.out.println("✓ [16] invalid token rejected: " + e.getMessage());
            passed++;
        }

        // --- 17. LIST root ---
        List<FileInfo> rootEntries = svc.list(token, "");
        System.out.println("✓ [17] ls (root) → " + rootEntries.size() + " item(s)");
        passed++;

        // --- Summary ---
        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed == 0) {
            System.out.println("ALL TESTS PASSED ✓");
        } else {
            System.out.println("SOME TESTS FAILED ✗");
            System.exit(1);
        }
    }
}
