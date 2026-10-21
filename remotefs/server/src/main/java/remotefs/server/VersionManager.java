package remotefs.server;

import remotefs.common.FileOpException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VersionManager — tracks file versions for optimistic concurrency control.
 *
 * CONCURRENCY DESIGN (viva talking point):
 *
 *   The "lost update" problem: Client A reads a file (version 1), Client B
 *   also reads it (version 1). B writes new content → version becomes 2.
 *   Then A writes — overwriting B's changes without knowing they existed.
 *
 *   VersionManager prevents this with OPTIMISTIC CONCURRENCY CONTROL:
 *     - Every file has an integer version that starts at 0 and increments
 *       on each successful write.
 *     - The write() method requires an expectedVersion parameter. If the
 *       current version doesn't match, the write is rejected with a
 *       "version conflict" error. The client must re-read and retry.
 *
 *   This is "optimistic" because we don't lock on read — we only check for
 *   conflicts at write time. It works well when conflicts are rare (which
 *   is the common case in file systems).
 *
 * PERSISTENCE:
 *   Versions are stored in-memory in a ConcurrentHashMap for fast access,
 *   and persisted to a .versions properties file in the storage root so
 *   they survive server restarts.
 *
 * THREAD SAFETY:
 *   - checkAndIncrement() is called inside the write lock held by LockManager,
 *     so only one thread can modify a given file's version at a time.
 *   - ConcurrentHashMap handles concurrent access to different keys safely.
 *   - persistVersions() is synchronized to prevent two threads from writing
 *     the properties file simultaneously.
 */
public class VersionManager {

    /**
     * In-memory version map. Key = normalised relative path (from PathValidator.toKey()).
     * Value = current version number (starts at 0 for new files).
     */
    private final ConcurrentHashMap<String, Integer> versions = new ConcurrentHashMap<>();

    /** Path to the persistence file (e.g. server_data/.versions). */
    private final Path metadataFile;

    /**
     * Creates a VersionManager and loads any previously persisted versions.
     *
     * @param storageRoot the storage root directory (e.g. server_data/)
     */
    public VersionManager(Path storageRoot) {
        this.metadataFile = storageRoot.resolve(".versions");
        loadVersions();
    }

    /**
     * Returns the current version of a file.
     *
     * @param key normalised path key (from PathValidator.toKey())
     * @return the current version, or 0 if the file has never been written through RemoteFS
     */
    public int getVersion(String key) {
        return versions.getOrDefault(key, 0);
    }

    /**
     * Checks that the expected version matches the current version, then
     * increments and returns the new version.
     *
     * MUST be called while holding the write lock for this path (via LockManager).
     * The lock guarantees that no other thread can modify the version between
     * the check and the increment — eliminating the TOCTOU (time-of-check to
     * time-of-use) race condition.
     *
     * @param key             normalised path key
     * @param expectedVersion the version the client believes is current
     * @return the new version after incrementing
     * @throws FileOpException if expectedVersion does not match (version conflict)
     */
    public int checkAndIncrement(String key, int expectedVersion) throws FileOpException {
        int current = versions.getOrDefault(key, 0);

        // --- Conflict detection ---
        // If another client wrote since this client last read, the versions won't match.
        if (current != expectedVersion) {
            throw new FileOpException(
                    "Version conflict: expected version " + expectedVersion
                    + " but current version is " + current
                    + ". Re-read the file and try again.");
        }

        // --- Increment and store ---
        int newVersion = current + 1;
        versions.put(key, newVersion);

        // Persist to disk so versions survive a server restart
        persistVersions();

        return newVersion;
    }

    /**
     * Unconditionally increments the version for a file.
     * Used by append(), which does not check expectedVersion.
     *
     * MUST be called while holding the write lock for this path.
     *
     * @param key normalised path key
     * @return the new version after incrementing
     */
    public int incrementVersion(String key) {
        int current = versions.getOrDefault(key, 0);
        int newVersion = current + 1;
        versions.put(key, newVersion);
        persistVersions();
        return newVersion;
    }

    /**
     * Removes the version entry for a deleted file.
     * MUST be called while holding the write lock for this path.
     *
     * @param key normalised path key
     */
    public void removeVersion(String key) {
        versions.remove(key);
        persistVersions();
    }

    /**
     * Transfers a version entry from one key to another (for rename).
     * MUST be called while holding write locks on both paths.
     *
     * @param oldKey the source file's path key
     * @param newKey the destination file's path key
     */
    public void renameVersion(String oldKey, String newKey) {
        Integer version = versions.remove(oldKey);
        if (version != null) {
            versions.put(newKey, version);
        }
        persistVersions();
    }

    // =====================================================================
    //  Persistence: load/save versions to a .properties file
    // =====================================================================

    /**
     * Loads versions from the .versions file at startup.
     * If the file doesn't exist (first run), this is a no-op.
     */
    private void loadVersions() {
        if (!Files.exists(metadataFile)) {
            return;
        }
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(metadataFile)) {
            props.load(reader);
            for (String key : props.stringPropertyNames()) {
                try {
                    versions.put(key, Integer.parseInt(props.getProperty(key)));
                } catch (NumberFormatException e) {
                    // Skip corrupt entries — don't crash the server
                    System.err.println("[VersionManager] Skipping corrupt version entry: " + key);
                }
            }
            System.out.println("[VersionManager] Loaded " + versions.size() + " version entries.");
        } catch (IOException e) {
            System.err.println("[VersionManager] Warning: could not load versions file: " + e.getMessage());
        }
    }

    /**
     * Persists the current version map to disk.
     * Synchronized to prevent two threads from writing simultaneously
     * (each would be working on a different file's version, but they
     * share the same output file).
     */
    private synchronized void persistVersions() {
        Properties props = new Properties();
        versions.forEach((key, version) -> props.setProperty(key, String.valueOf(version)));
        try (var writer = Files.newBufferedWriter(metadataFile)) {
            props.store(writer, "RemoteFS file version metadata — do not edit manually");
        } catch (IOException e) {
            System.err.println("[VersionManager] Warning: could not persist versions: " + e.getMessage());
        }
    }
}
