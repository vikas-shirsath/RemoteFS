package remotefs.server;

import remotefs.common.FileInfo;
import remotefs.common.FileOpException;
import remotefs.common.FileService;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FileServiceImpl — the RMI remote object that implements the FileService interface.
 *
 * HOW RMI REMOTE OBJECTS WORK (viva talking point):
 *   This class extends UnicastRemoteObject, which does two things at
 *   construction time:
 *     1. Exports the object — opens a TCP server socket so that RMI can
 *        receive incoming method calls from remote clients.
 *     2. Creates a stub — a serialisable proxy object that the client
 *        receives from the registry. When the client calls a method on
 *        the stub, the stub serialises the arguments, sends them over TCP,
 *        this server deserialises them, invokes the real method here,
 *        serialises the return value, and sends it back.
 *
 * OVERALL FLOW FOR EVERY METHOD:
 *   1. Validate the session token  (→ AuthManager)
 *   2. Validate and resolve the path (→ PathValidator)
 *   3. Compute the lock/version key  (→ PathValidator.toKey)
 *   4. Acquire the appropriate lock   (→ LockManager)
 *   5. Perform the file I/O operation (→ java.nio.file.Files)
 *   6. Release the lock in a finally block (always — even on error)
 *   7. Log the operation              (→ AuditLogger)
 *
 * ERROR HANDLING:
 *   - All IOExceptions are caught and wrapped in FileOpException with a
 *     clean message. The client never sees raw stack traces.
 *   - Every method logs both success and failure to the audit log.
 *   - Audit logging failures are swallowed (they must not break file ops).
 */
public class FileServiceImpl extends UnicastRemoteObject implements FileService {

    private static final long serialVersionUID = 1L;

    /** The root directory for all file storage. */
    private final Path storageRoot;

    /** Validates and resolves user-supplied paths against the storage root. */
    private final PathValidator pathValidator;

    /** Manages per-file read-write locks with fair ordering and timeouts. */
    private final LockManager lockManager;

    /** Tracks file versions for optimistic concurrency control. */
    private final VersionManager versionManager;

    /** Authenticates users and validates session tokens. */
    private final AuthManager authManager;

    /** Records every operation to an append-only audit log. */
    private final AuditLogger auditLogger;

    /**
     * Creates the FileServiceImpl and exports it as an RMI remote object.
     *
     * The super() call (UnicastRemoteObject constructor) exports this object
     * on a random port by default. To use a fixed port, call super(port).
     *
     * @param storageRoot the directory where all files are stored
     * @throws RemoteException if the RMI export fails
     */
    public FileServiceImpl(Path storageRoot) throws RemoteException {
        super(); // Exports this object for RMI (opens a TCP socket)

        this.storageRoot = storageRoot.toAbsolutePath().normalize();

        // Ensure the storage directory exists BEFORE initialising components
        // that write into it (AuthManager creates users.properties,
        // VersionManager loads .versions).
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException e) {
            throw new RemoteException("Failed to create storage directory", e);
        }

        this.pathValidator = new PathValidator(this.storageRoot);
        this.lockManager = new LockManager();
        this.versionManager = new VersionManager(this.storageRoot);
        this.authManager = new AuthManager(this.storageRoot);
        this.auditLogger = new AuditLogger(this.storageRoot);

        System.out.println("[FileServiceImpl] Initialised with storage root: " + this.storageRoot);
    }

    // =====================================================================
    //  Accessors for testing — package-private so tests can inspect state
    // =====================================================================

    PathValidator getPathValidator() { return pathValidator; }
    LockManager getLockManager()     { return lockManager; }
    VersionManager getVersionManager() { return versionManager; }
    AuthManager getAuthManager()     { return authManager; }

    // =====================================================================
    //  login — authenticates user and returns session token
    // =====================================================================

    /**
     * {@inheritDoc}
     *
     * Delegates to AuthManager which verifies the password against a salted
     * SHA-256 hash and, on success, issues a random UUID session token.
     *
     * This is the ONLY method that does not require a token — it's how
     * the client obtains one.
     */
    @Override
    public String login(String user, String password) throws RemoteException, FileOpException {
        try {
            String token = authManager.login(user, password);
            auditLogger.log(user, "LOGIN", "-", "OK");
            return token;
        } catch (FileOpException e) {
            // Log the failed attempt with the username (may be null/blank)
            auditLogger.log(user != null ? user : "unknown", "LOGIN", "-",
                    "FAIL: " + e.getMessage());
            throw e;
        }
    }

    // =====================================================================
    //  list — directory listing (no file lock needed)
    // =====================================================================

    /**
     * {@inheritDoc}
     *
     * No file lock is acquired because listing a directory is a point-in-time
     * snapshot — the directory entries can change immediately after we list
     * them anyway. This is consistent with how ls/dir work on real file systems.
     */
    @Override
    public List<FileInfo> list(String token, String dir) throws RemoteException, FileOpException {
        String user = "unknown";
        try {
            // --- Step 1: Authenticate ---
            user = authManager.validateToken(token);

            // --- Step 2: Resolve path ---
            Path dirPath = pathValidator.resolve(dir);

            if (!Files.exists(dirPath)) {
                throw new FileOpException("Directory not found: " + dir);
            }
            if (!Files.isDirectory(dirPath)) {
                throw new FileOpException("Not a directory: " + dir);
            }

            // --- Step 3: List directory contents ---
            List<FileInfo> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();

                    // Skip internal metadata files
                    if (name.equals(".versions") || name.equals("audit.log")
                            || name.equals("users.properties")) {
                        continue;
                    }

                    boolean isDir = Files.isDirectory(entry);
                    long size = isDir ? 0 : Files.size(entry);
                    long lastModified = Files.getLastModifiedTime(entry).toMillis();

                    // Look up the version for this file (0 for directories)
                    String key = pathValidator.toKey(entry);
                    int version = isDir ? 0 : versionManager.getVersion(key);

                    entries.add(new FileInfo(name, size, isDir, lastModified, version));
                }
            } catch (IOException e) {
                throw new FileOpException("Failed to list directory: " + dir);
            }

            auditLogger.log(user, "LIST", dir.isEmpty() ? "." : dir, "OK");
            return entries;

        } catch (FileOpException e) {
            auditLogger.log(user, "LIST", dir != null ? dir : "-",
                    "FAIL: " + e.getMessage());
            throw e;
        }
    }

    // =====================================================================
    //  read — acquires READ lock (shared; concurrent readers allowed)
    // =====================================================================

    /**
     * {@inheritDoc}
     *
     * LOCKING (viva talking point):
     *   We acquire a READ lock, which is SHARED — multiple threads can
     *   read the same file at the same time. However, a READ lock blocks
     *   any thread trying to acquire a WRITE lock, so a writer must wait
     *   until all readers are done. This prevents reading half-written content.
     */
    @Override
    public byte[] read(String token, String path) throws RemoteException, FileOpException {
        String user = "unknown";
        try {
            // --- Step 1: Authenticate ---
            user = authManager.validateToken(token);

            // --- Step 2: Resolve path ---
            Path safePath = pathValidator.resolve(path);
            String key = pathValidator.toKey(safePath);

            // --- Step 3: Acquire read lock ---
            lockManager.acquireReadLock(key);
            try {
                // All I/O happens inside the lock. If an exception occurs,
                // the finally block still releases the lock.
                if (!Files.exists(safePath)) {
                    throw new FileOpException("File not found: " + path);
                }
                if (Files.isDirectory(safePath)) {
                    throw new FileOpException("Cannot read a directory (use 'list' instead): " + path);
                }

                byte[] data = Files.readAllBytes(safePath);
                auditLogger.log(user, "READ", path, "OK (" + data.length + " bytes)");
                return data;

            } finally {
                // --- ALWAYS release the lock, even if an exception was thrown ---
                lockManager.releaseReadLock(key);
            }

        } catch (FileOpException e) {
            auditLogger.log(user, "READ", path, "FAIL: " + e.getMessage());
            throw e;
        } catch (IOException e) {
            auditLogger.log(user, "READ", path, "FAIL: I/O error");
            throw new FileOpException("Failed to read file: " + path);
        }
    }

    // =====================================================================
    //  write — atomic write with version conflict detection
    // =====================================================================

    /**
     * {@inheritDoc}
     *
     * ATOMIC WRITE STRATEGY (viva talking point):
     *   We do NOT write directly to the target file. Instead:
     *     1. Write data to a TEMPORARY file in the same directory
     *     2. Use Files.move() with ATOMIC_MOVE to rename the temp file
     *        over the target file
     *
     *   Why? An ATOMIC_MOVE is implemented by the operating system as a
     *   single, indivisible pointer update in the file system directory.
     *   At no point does the target file contain partial content:
     *     - Before the move: the old content is intact
     *     - After the move:  the new content is intact
     *   Any reader that opens the file will see one or the other, never a mix.
     *
     * VERSION CONFLICT DETECTION (viva talking point):
     *   The expectedVersion parameter prevents lost updates. The client
     *   must first call stat() to read the current version, then pass it
     *   to write(). If another client has written in the meantime, the
     *   version will have changed and checkAndIncrement() throws
     *   FileOpException("version conflict").
     *
     *   Both the version check and the increment happen INSIDE the write
     *   lock, so there is no TOCTOU (time-of-check to time-of-use) race.
     */
    @Override
    public int write(String token, String path, byte[] data, int expectedVersion)
            throws RemoteException, FileOpException {
        String user = "unknown";
        try {
            // --- Step 1: Authenticate ---
            user = authManager.validateToken(token);

            // --- Step 2: Resolve path ---
            Path safePath = pathValidator.resolve(path);
            String key = pathValidator.toKey(safePath);

            // --- Step 3: Acquire exclusive write lock ---
            lockManager.acquireWriteLock(key);
            try {
                // Create parent directories if they don't exist yet
                Path parent = safePath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }

                // --- Step 4: Version conflict check (inside write lock → no TOCTOU) ---
                int newVersion = versionManager.checkAndIncrement(key, expectedVersion);

                // --- Step 5: Atomic write: temp file + ATOMIC_MOVE ---

                // Write data to a temporary file in the same directory.
                // Using the same directory ensures the temp file is on the
                // same filesystem partition, which is required for ATOMIC_MOVE.
                Path tempFile = Files.createTempFile(parent, ".remotefs-", ".tmp");
                try {
                    Files.write(tempFile, data);

                    // Atomically move the temp file over the target.
                    // ATOMIC_MOVE guarantees the rename is a single operation.
                    // REPLACE_EXISTING overwrites the target if it already exists.
                    Files.move(tempFile, safePath,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    // If the atomic move failed, clean up the temp file
                    Files.deleteIfExists(tempFile);
                    throw e;
                }

                auditLogger.log(user, "WRITE", path,
                        "OK (v" + newVersion + ", " + data.length + " bytes)");
                return newVersion;

            } finally {
                // --- ALWAYS release the write lock ---
                lockManager.releaseWriteLock(key);
            }

        } catch (FileOpException e) {
            auditLogger.log(user, "WRITE", path, "FAIL: " + e.getMessage());
            throw e;
        } catch (IOException e) {
            auditLogger.log(user, "WRITE", path, "FAIL: I/O error");
            throw new FileOpException("Failed to write file: " + path);
        }
    }

    // =====================================================================
    //  append — adds data to end of file (no version check)
    // =====================================================================

    /**
     * {@inheritDoc}
     *
     * Unlike write(), append does NOT check expectedVersion because appending
     * data to the end of a file cannot cause a "lost update" — each append
     * simply adds its bytes after whatever is already there.
     *
     * LOCKING: uses a WRITE lock (exclusive) because we are modifying the file.
     * Multiple concurrent appends are serialised by the write lock, so each
     * append's data appears contiguously in the file without interleaving.
     */
    @Override
    public void append(String token, String path, byte[] data) throws RemoteException, FileOpException {
        String user = "unknown";
        try {
            // --- Step 1: Authenticate ---
            user = authManager.validateToken(token);

            // --- Step 2: Resolve path ---
            Path safePath = pathValidator.resolve(path);
            String key = pathValidator.toKey(safePath);

            // --- Step 3: Acquire exclusive write lock ---
            lockManager.acquireWriteLock(key);
            try {
                // Create parent directories if needed
                Path parent = safePath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }

                // Append data to the file; CREATE if it doesn't exist.
                // StandardOpenOption.APPEND moves the write position to the end.
                // StandardOpenOption.CREATE creates the file if missing.
                Files.write(safePath, data,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.CREATE);

                // Increment the version (unconditionally — no expectedVersion check)
                versionManager.incrementVersion(key);

                auditLogger.log(user, "APPEND", path, "OK (+" + data.length + " bytes)");

            } finally {
                lockManager.releaseWriteLock(key);
            }

        } catch (FileOpException e) {
            auditLogger.log(user, "APPEND", path, "FAIL: " + e.getMessage());
            throw e;
        } catch (IOException e) {
            auditLogger.log(user, "APPEND", path, "FAIL: I/O error");
            throw new FileOpException("Failed to append to file: " + path);
        }
    }

    // =====================================================================
    //  delete — removes a file or empty directory
    // =====================================================================

    /**
     * {@inheritDoc}
     *
     * LOCKING: uses a WRITE lock so no one can read or write the file
     * while it is being deleted.
     */
    @Override
    public void delete(String token, String path) throws RemoteException, FileOpException {
        String user = "unknown";
        try {
            // --- Step 1: Authenticate ---
            user = authManager.validateToken(token);

            // --- Step 2: Resolve path ---
            Path safePath = pathValidator.resolve(path);
            String key = pathValidator.toKey(safePath);

            // --- Step 3: Acquire exclusive write lock ---
            lockManager.acquireWriteLock(key);
            try {
                if (!Files.exists(safePath)) {
                    throw new FileOpException("File not found: " + path);
                }

                // Files.delete throws DirectoryNotEmptyException for non-empty dirs.
                // This is intentional — we only allow deleting empty directories.
                Files.delete(safePath);

                // Clean up the version entry
                versionManager.removeVersion(key);

                auditLogger.log(user, "DELETE", path, "OK");

            } finally {
                lockManager.releaseWriteLock(key);
            }

        } catch (FileOpException e) {
            auditLogger.log(user, "DELETE", path, "FAIL: " + e.getMessage());
            throw e;
        } catch (IOException e) {
            auditLogger.log(user, "DELETE", path, "FAIL: I/O error");
            throw new FileOpException("Failed to delete: " + path
                    + " (if directory, it must be empty)");
        }
    }

    // =====================================================================
    //  rename — move/rename with sorted dual-lock for deadlock avoidance
    // =====================================================================

    /**
     * {@inheritDoc}
     *
     * DEADLOCK AVOIDANCE (viva talking point):
     *   Renaming requires locking both the source and destination paths.
     *   If Thread 1 renames A→B (locks A then B) while Thread 2 renames
     *   B→A (locks B then A), we get circular wait → deadlock.
     *
     *   Solution: LockManager.acquireWriteLocksOrdered() always locks paths
     *   in alphabetical order. Both threads lock A first, then B. Since they
     *   compete for A first, one waits for the other to finish — no cycle.
     */
    @Override
    public void rename(String token, String from, String to) throws RemoteException, FileOpException {
        String user = "unknown";
        try {
            // --- Step 1: Authenticate ---
            user = authManager.validateToken(token);

            // --- Step 2: Resolve both paths ---
            Path fromPath = pathValidator.resolve(from);
            Path toPath = pathValidator.resolve(to);
            String fromKey = pathValidator.toKey(fromPath);
            String toKey = pathValidator.toKey(toPath);

            // --- Step 3: Acquire write locks on BOTH paths in sorted order ---
            lockManager.acquireWriteLocksOrdered(fromKey, toKey);
            try {
                if (!Files.exists(fromPath)) {
                    throw new FileOpException("Source not found: " + from);
                }
                if (Files.exists(toPath)) {
                    throw new FileOpException("Destination already exists: " + to);
                }

                // Create parent directories of the destination if needed
                Path toParent = toPath.getParent();
                if (toParent != null && !Files.exists(toParent)) {
                    Files.createDirectories(toParent);
                }

                // Perform the rename
                Files.move(fromPath, toPath);

                // Transfer the version entry from old path to new path
                versionManager.renameVersion(fromKey, toKey);

                auditLogger.log(user, "RENAME", from + " → " + to, "OK");

            } finally {
                // --- ALWAYS release BOTH locks ---
                lockManager.releaseWriteLocks(fromKey, toKey);
            }

        } catch (FileOpException e) {
            auditLogger.log(user, "RENAME", from + " → " + to,
                    "FAIL: " + e.getMessage());
            throw e;
        } catch (IOException e) {
            auditLogger.log(user, "RENAME", from + " → " + to, "FAIL: I/O error");
            throw new FileOpException("Failed to rename " + from + " to " + to);
        }
    }

    // =====================================================================
    //  mkdir — create directory
    // =====================================================================

    /**
     * {@inheritDoc}
     *
     * No lock is needed because creating a new directory doesn't conflict
     * with any existing file's read/write operations.
     */
    @Override
    public void mkdir(String token, String path) throws RemoteException, FileOpException {
        String user = "unknown";
        try {
            // --- Step 1: Authenticate ---
            user = authManager.validateToken(token);

            // --- Step 2: Resolve path ---
            Path dirPath = pathValidator.resolve(path);

            // createDirectories() creates any missing parent dirs too.
            Files.createDirectories(dirPath);

            auditLogger.log(user, "MKDIR", path, "OK");

        } catch (FileOpException e) {
            auditLogger.log(user, "MKDIR", path, "FAIL: " + e.getMessage());
            throw e;
        } catch (IOException e) {
            auditLogger.log(user, "MKDIR", path, "FAIL: I/O error");
            throw new FileOpException("Failed to create directory: " + path);
        }
    }

    // =====================================================================
    //  stat — file metadata
    // =====================================================================

    /**
     * {@inheritDoc}
     *
     * No lock is needed because stat returns a point-in-time snapshot of
     * metadata, similar to how 'ls -l' works on a real file system.
     */
    @Override
    public FileInfo stat(String token, String path) throws RemoteException, FileOpException {
        String user = "unknown";
        try {
            // --- Step 1: Authenticate ---
            user = authManager.validateToken(token);

            // --- Step 2: Resolve path ---
            Path safePath = pathValidator.resolve(path);

            if (!Files.exists(safePath)) {
                throw new FileOpException("File not found: " + path);
            }

            String name = safePath.getFileName() != null
                    ? safePath.getFileName().toString()
                    : safePath.toString();
            boolean isDir = Files.isDirectory(safePath);
            long size = isDir ? 0 : Files.size(safePath);
            long lastModified = Files.getLastModifiedTime(safePath).toMillis();

            String key = pathValidator.toKey(safePath);
            int version = isDir ? 0 : versionManager.getVersion(key);

            auditLogger.log(user, "STAT", path, "OK");
            return new FileInfo(name, size, isDir, lastModified, version);

        } catch (FileOpException e) {
            auditLogger.log(user, "STAT", path, "FAIL: " + e.getMessage());
            throw e;
        } catch (IOException e) {
            auditLogger.log(user, "STAT", path, "FAIL: I/O error");
            throw new FileOpException("Failed to stat: " + path);
        }
    }
}
