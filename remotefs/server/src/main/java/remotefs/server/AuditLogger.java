package remotefs.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * AuditLogger — records every file operation to an append-only audit log.
 *
 * SECURITY DESIGN (viva talking point):
 *   An audit log is essential for accountability and forensics. If a file
 *   is deleted or corrupted, the audit log shows who did it and when.
 *
 *   The log is append-only: entries can never be modified or deleted through
 *   the RemoteFS interface. Each entry records:
 *     - Timestamp (ISO-8601 format)
 *     - Authenticated username
 *     - Operation name (LOGIN, READ, WRITE, DELETE, etc.)
 *     - File path involved
 *     - Result (OK or FAIL with reason)
 *
 * THREAD SAFETY:
 *   Multiple RMI threads may log simultaneously. The log() method is
 *   synchronized to prevent interleaved writes that would corrupt entries.
 *   For a high-throughput production system you'd use a lock-free queue
 *   with a dedicated writer thread, but synchronized is perfectly fine
 *   for a lab project.
 *
 * LOG FORMAT EXAMPLE:
 *   [2024-01-15T10:30:00.123] user=alice     op=WRITE    path=docs/readme.txt   result=OK
 *   [2024-01-15T10:30:01.456] user=bob       op=DELETE   path=temp/old.txt      result=FAIL: File not found
 */
public class AuditLogger {

    /** The audit log file (e.g. server_data/audit.log). */
    private final Path logFile;

    /** Human-readable timestamp formatter. */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    /**
     * Creates an AuditLogger that writes to audit.log in the storage root.
     *
     * @param storageRoot the server's storage root directory
     */
    public AuditLogger(Path storageRoot) {
        this.logFile = storageRoot.resolve("audit.log");
    }

    /**
     * Records a single audit entry.
     *
     * THREAD SAFETY (viva talking point):
     *   This method is synchronized so that only one thread can write to
     *   the log file at a time. Without synchronisation, two threads could
     *   write simultaneously and their output could interleave mid-line,
     *   producing corrupt log entries.
     *
     * @param user      the authenticated username (or "unknown" if auth failed)
     * @param operation the operation name (e.g. "READ", "WRITE", "LOGIN")
     * @param path      the file path involved (use "-" for operations without a path)
     * @param result    "OK" for success, or "FAIL: <reason>" for failure
     */
    public synchronized void log(String user, String operation, String path, String result) {
        // Format the log entry with fixed-width columns for readability
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        String entry = String.format("[%s] user=%-10s op=%-8s path=%-30s result=%s%n",
                timestamp, user, operation, path, result);

        try {
            // APPEND: add to end of file without overwriting existing entries
            // CREATE: create the file if it doesn't exist yet (first log entry)
            Files.writeString(logFile, entry,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.CREATE);
        } catch (IOException e) {
            // Don't throw — logging failure should not break the file operation.
            // Print to stderr so the server operator notices.
            System.err.println("[AuditLogger] WARNING: Failed to write audit entry: " + e.getMessage());
            System.err.println("[AuditLogger] Entry was: " + entry.trim());
        }
    }
}
