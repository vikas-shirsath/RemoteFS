package remotefs.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * FileService — the central RMI remote interface for RemoteFS.
 *
 * HOW RMI WORKS (viva talking point):
 *   1. This interface extends java.rmi.Remote, which tells the RMI runtime
 *      that its methods can be invoked from another JVM (the client).
 *   2. Every method must declare "throws RemoteException" because network
 *      calls can fail (timeout, disconnect, serialisation error, etc.).
 *   3. All parameter and return types must be Serializable so the RMI
 *      runtime can marshal them across the network.
 *   4. On the server side, FileServiceImpl implements this interface and
 *      extends UnicastRemoteObject, which exports it on a TCP port.
 *   5. The server binds the object to an RMI registry under the name
 *      "FileService". The client looks it up by that name and receives
 *      a stub — a local proxy that forwards every call over the network.
 *
 * SECURITY:
 *   Every method (except login) requires a session token obtained from
 *   login(). The server validates the token on every call so that
 *   unauthenticated clients cannot access files.
 */
public interface FileService extends Remote {

    /**
     * Authenticate a user and obtain a session token.
     *
     * @param user     the username
     * @param password the plaintext password (hashed server-side)
     * @return a random session token (UUID string)
     * @throws FileOpException if credentials are invalid
     */
    String login(String user, String password) throws RemoteException, FileOpException;

    /**
     * List the contents of a directory.
     *
     * @param token session token from login()
     * @param dir   directory path relative to the storage root (use "" or "." for root)
     * @return list of FileInfo entries; empty list if directory is empty
     */
    List<FileInfo> list(String token, String dir) throws RemoteException, FileOpException;

    /**
     * Read the entire contents of a file.
     * Acquires a READ lock — multiple concurrent readers are allowed,
     * but a writer must wait until all readers release.
     *
     * @param token session token
     * @param path  file path relative to the storage root
     * @return the file content as a byte array
     */
    byte[] read(String token, String path) throws RemoteException, FileOpException;

    /**
     * Write (overwrite) a file with new content.
     *
     * CONCURRENCY DESIGN (viva talking point):
     *   - Acquires a WRITE lock (exclusive — blocks readers and other writers).
     *   - Uses optimistic concurrency control via expectedVersion:
     *     the client reads the current version with stat(), then passes it
     *     here. If another client wrote in between, the version will have
     *     changed and this call rejects with "version conflict".
     *   - The actual I/O is atomic: data is written to a temp file first,
     *     then renamed over the target with Files.move(ATOMIC_MOVE).
     *     This guarantees readers never see partial/corrupt content.
     *
     * @param token           session token
     * @param path            file path relative to the storage root
     * @param data            new file content
     * @param expectedVersion the version the client believes is current
     *                        (0 for a brand-new file)
     * @return the new version number after this write
     */
    int write(String token, String path, byte[] data, int expectedVersion)
            throws RemoteException, FileOpException;

    /**
     * Append data to the end of a file (creates the file if it doesn't exist).
     * Acquires a WRITE lock but does NOT check the version — appends are
     * unconditional (they just add data, so there is no "lost update" risk
     * like with full overwrites).
     *
     * @param token session token
     * @param path  file path
     * @param data  bytes to append
     */
    void append(String token, String path, byte[] data) throws RemoteException, FileOpException;

    /**
     * Delete a file or empty directory.
     * Acquires a WRITE lock to ensure no one is reading or writing during deletion.
     *
     * @param token session token
     * @param path  path to delete
     */
    void delete(String token, String path) throws RemoteException, FileOpException;

    /**
     * Rename / move a file or directory.
     *
     * DEADLOCK AVOIDANCE (viva talking point):
     *   Acquires WRITE locks on both the source and destination paths.
     *   To prevent deadlock when two clients rename in opposite directions
     *   (A→B and B→A simultaneously), locks are always acquired in
     *   sorted alphabetical order. This is the "resource ordering" technique
     *   from operating systems theory.
     *
     * @param token session token
     * @param from  current path
     * @param to    new path
     */
    void rename(String token, String from, String to) throws RemoteException, FileOpException;

    /**
     * Create a directory (and any missing parent directories).
     *
     * @param token session token
     * @param path  directory path to create
     */
    void mkdir(String token, String path) throws RemoteException, FileOpException;

    /**
     * Get metadata about a file or directory.
     *
     * @param token session token
     * @param path  path to query
     * @return a FileInfo with name, size, isDirectory, lastModified, version
     */
    FileInfo stat(String token, String path) throws RemoteException, FileOpException;
}
