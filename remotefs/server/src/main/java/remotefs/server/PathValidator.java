package remotefs.server;

import remotefs.common.FileOpException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * PathValidator — resolves and sanitises every user-supplied path against
 * a fixed storage root directory, preventing directory-traversal attacks.
 *
 * SECURITY DESIGN (viva talking point):
 *   A malicious client could send paths like "../../etc/passwd" or
 *   "/etc/shadow" to escape the storage directory and read arbitrary
 *   files on the server. PathValidator prevents this by:
 *
 *   1. Rejecting absolute paths outright (anything starting with / or C:\)
 *   2. Resolving the user path against the storage root and normalising it
 *      (collapsing "." and ".." segments)
 *   3. Checking that the resolved path still starts with the storage root
 *   4. If the path already exists on disk, resolving symlinks and checking
 *      again (a symlink could point outside the root)
 *
 *   Example attack that gets blocked:
 *     storageRoot = /srv/remotefs/server_data
 *     userPath    = "../../etc/passwd"
 *     resolved    = /srv/remotefs/server_data/../../etc/passwd
 *     normalized  = /srv/etc/passwd        ← does NOT start with storageRoot → REJECTED
 */
public class PathValidator {

    /** The absolute, normalised root directory for all file storage. */
    private final Path storageRoot;

    /**
     * @param storageRoot the directory that acts as the "file system root"
     *                    for all client operations (e.g. server_data/)
     */
    public PathValidator(Path storageRoot) {
        // toAbsolutePath() makes relative paths absolute,
        // normalize() collapses redundant ".." and "." segments.
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    /**
     * Resolves a user-supplied path to a safe absolute path under the storage root.
     *
     * @param userPath a relative path supplied by the client (e.g. "docs/readme.txt")
     * @return the resolved, validated absolute Path on disk
     * @throws FileOpException if the path is invalid or escapes the storage root
     */
    public Path resolve(String userPath) throws FileOpException {
        // --- Step 1: reject null / blank input ---
        if (userPath == null || userPath.isBlank()) {
            // Treat empty path as the root directory itself
            return storageRoot;
        }

        // --- Step 2: reject absolute paths ---
        // An absolute path would bypass the storage root entirely.
        if (userPath.startsWith("/") || userPath.startsWith("\\")) {
            throw new FileOpException("Absolute paths are not allowed: " + userPath);
        }
        // Windows drive letters like C:\ or D:/
        if (userPath.length() >= 2 && userPath.charAt(1) == ':') {
            throw new FileOpException("Absolute paths are not allowed: " + userPath);
        }

        // --- Step 3: resolve against storage root and normalise ---
        // resolve() appends the userPath to storageRoot.
        // normalize() collapses ".." and "." so we can do a prefix check.
        Path resolved = storageRoot.resolve(userPath).normalize();

        // --- Step 4: prefix check — must still be under the storage root ---
        if (!resolved.startsWith(storageRoot)) {
            throw new FileOpException("Access denied: path escapes storage root");
        }

        // --- Step 5: symlink check (only if the path already exists) ---
        // A file could be a symlink that points outside the storage root.
        // toRealPath() resolves all symlinks to the actual file system location.
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path realPath = resolved.toRealPath();
                Path realRoot = storageRoot.toRealPath();
                if (!realPath.startsWith(realRoot)) {
                    throw new FileOpException("Access denied: symlink escapes storage root");
                }
            } catch (IOException e) {
                throw new FileOpException("Cannot resolve path: " + userPath);
            }
        } else {
            // File doesn't exist yet (e.g. creating a new file).
            // Check that the PARENT directory is valid and under the root.
            Path parent = resolved.getParent();
            if (parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Path realParent = parent.toRealPath();
                    Path realRoot = storageRoot.toRealPath();
                    if (!realParent.startsWith(realRoot)) {
                        throw new FileOpException("Access denied: parent path escapes storage root");
                    }
                } catch (IOException e) {
                    throw new FileOpException("Cannot resolve parent path: " + userPath);
                }
            }
        }

        return resolved;
    }

    /**
     * Converts an absolute resolved path into a normalised key string
     * suitable for use in LockManager and VersionManager.
     *
     * The key is the path relative to the storage root, with forward slashes,
     * so it is consistent across platforms (Windows uses \ by default).
     *
     * Example:
     *   storageRoot = C:\srv\server_data
     *   resolvedPath = C:\srv\server_data\docs\readme.txt
     *   key = "docs/readme.txt"
     *
     * @param resolvedPath an absolute path previously validated by resolve()
     * @return a normalised relative path string
     */
    public String toKey(Path resolvedPath) {
        // relativize() strips the storageRoot prefix, leaving the relative part.
        // Replace backslashes with forward slashes for platform independence.
        return storageRoot.relativize(resolvedPath)
                .toString()
                .replace('\\', '/');
    }

    /**
     * @return the absolute storage root path
     */
    public Path getStorageRoot() {
        return storageRoot;
    }
}
