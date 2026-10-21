package remotefs.server;

import remotefs.common.FileOpException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LockManager — manages per-file read-write locks for concurrency control.
 *
 * CONCURRENCY DESIGN (viva talking points):
 *
 *   1. DATA STRUCTURE: A ConcurrentHashMap maps each normalised file path
 *      to its own ReentrantReadWriteLock. ConcurrentHashMap is thread-safe
 *      and lock-free for reads, so looking up a lock is very fast.
 *
 *   2. FAIR LOCKS: Each lock is created with fairness=true, meaning threads
 *      are granted the lock in the order they requested it (FIFO). Without
 *      fairness, a steady stream of readers could starve a waiting writer
 *      indefinitely. Fair locks prevent this at a small throughput cost.
 *
 *   3. READ-WRITE SEPARATION:
 *      - Read lock:  SHARED — multiple threads can hold it simultaneously.
 *                    Used by read() so concurrent reads don't block each other.
 *      - Write lock: EXCLUSIVE — only one thread can hold it, and no readers
 *                    are allowed. Used by write(), append(), delete(), rename().
 *
 *   4. tryLock WITH TIMEOUT: Instead of blocking forever with lock(), we use
 *      tryLock(5, SECONDS). If the lock is not available within 5 seconds,
 *      we throw a clean FileOpException("file is busy") rather than hanging.
 *      This prevents the client from waiting indefinitely.
 *
 *   5. DEADLOCK AVOIDANCE FOR RENAME: When rename() needs to lock two paths,
 *      we always acquire them in sorted alphabetical order. This is the
 *      "resource ordering" technique — if every thread acquires locks in the
 *      same global order, circular wait (deadlock) is impossible.
 *
 *      Example:  Thread 1 wants rename(A, B) — locks A then B
 *                Thread 2 wants rename(B, A) — locks A then B (same order!)
 *                No circular wait → no deadlock.
 */
public class LockManager {

    /** How long to wait for a lock before giving up (in seconds). */
    private static final long LOCK_TIMEOUT_SECONDS = 5;

    /**
     * Per-file lock map. Key = normalised relative path (from PathValidator.toKey()).
     *
     * computeIfAbsent() atomically creates a new lock the first time a file
     * is accessed, so there's no race condition in lock creation.
     */
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks =
            new ConcurrentHashMap<>();

    // =====================================================================
    //  Internal: get or create the lock for a given path
    // =====================================================================

    /**
     * Returns the ReentrantReadWriteLock for the given path, creating one
     * if it doesn't exist yet.
     *
     * @param normalizedPath the key from PathValidator.toKey()
     * @return the lock associated with this path
     */
    private ReentrantReadWriteLock getLock(String normalizedPath) {
        // computeIfAbsent is atomic: if two threads call this for the same
        // path at the same time, only one lock object is created.
        // The second argument (fair=true) enables FIFO ordering.
        return locks.computeIfAbsent(normalizedPath,
                key -> new ReentrantReadWriteLock(true /* fair */));
    }

    // =====================================================================
    //  Read lock: shared — allows concurrent readers
    // =====================================================================

    /**
     * Acquires a read lock on the file at the given path.
     * Multiple threads can hold the read lock simultaneously.
     * Blocks for up to 5 seconds; throws FileOpException if the lock
     * is not available (e.g. a writer is holding the exclusive lock).
     *
     * @param normalizedPath the path key to lock
     * @throws FileOpException if the lock cannot be acquired within the timeout
     */
    public void acquireReadLock(String normalizedPath) throws FileOpException {
        ReentrantReadWriteLock lock = getLock(normalizedPath);
        try {
            // tryLock returns true if the lock was acquired, false if timeout elapsed.
            boolean acquired = lock.readLock().tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new FileOpException("File is busy (read lock timeout): " + normalizedPath);
            }
        } catch (InterruptedException e) {
            // Restore the interrupt flag — this is a Java best practice.
            // If a thread is interrupted while waiting for a lock, we should
            // re-set the flag so the caller knows the thread was interrupted.
            Thread.currentThread().interrupt();
            throw new FileOpException("Lock acquisition interrupted");
        }
    }

    /**
     * Releases the read lock on the file at the given path.
     * Must be called in a finally block to guarantee release even if
     * an exception occurs during the read operation.
     *
     * @param normalizedPath the path key to unlock
     */
    public void releaseReadLock(String normalizedPath) {
        ReentrantReadWriteLock lock = locks.get(normalizedPath);
        if (lock != null) {
            lock.readLock().unlock();
        }
    }

    // =====================================================================
    //  Write lock: exclusive — only one thread at a time
    // =====================================================================

    /**
     * Acquires an exclusive write lock on the file at the given path.
     * Only one thread can hold the write lock, and no readers are allowed.
     * Blocks for up to 5 seconds.
     *
     * @param normalizedPath the path key to lock
     * @throws FileOpException if the lock cannot be acquired within the timeout
     */
    public void acquireWriteLock(String normalizedPath) throws FileOpException {
        ReentrantReadWriteLock lock = getLock(normalizedPath);
        try {
            boolean acquired = lock.writeLock().tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new FileOpException("File is busy (write lock timeout): " + normalizedPath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileOpException("Lock acquisition interrupted");
        }
    }

    /**
     * Releases the write lock on the file at the given path.
     * Must be called in a finally block.
     *
     * @param normalizedPath the path key to unlock
     */
    public void releaseWriteLock(String normalizedPath) {
        ReentrantReadWriteLock lock = locks.get(normalizedPath);
        if (lock != null) {
            lock.writeLock().unlock();
        }
    }

    // =====================================================================
    //  Ordered write locks for rename: deadlock avoidance
    // =====================================================================

    /**
     * Acquires exclusive write locks on TWO paths, in sorted alphabetical
     * order to prevent deadlock.
     *
     * WHY SORTED ORDER? (viva talking point)
     *   Deadlock requires a circular wait: Thread 1 holds lock A and waits
     *   for lock B, while Thread 2 holds lock B and waits for lock A.
     *   By always locking in alphabetical order (A before B), we break the
     *   cycle. Both threads will try to lock A first, so one will wait for
     *   the other to finish — no circular dependency.
     *
     * ERROR HANDLING:
     *   If the first lock succeeds but the second fails (timeout or interrupt),
     *   we release the first lock before propagating the exception. This
     *   prevents a thread from holding a lock it will never release.
     *
     * @param path1 first path to lock
     * @param path2 second path to lock
     * @throws FileOpException if either lock cannot be acquired
     */
    public void acquireWriteLocksOrdered(String path1, String path2) throws FileOpException {
        // Sort the two paths alphabetically to impose a total order.
        String first, second;
        if (path1.compareTo(path2) <= 0) {
            first = path1;
            second = path2;
        } else {
            first = path2;
            second = path1;
        }

        // Lock the alphabetically-first path
        acquireWriteLock(first);

        // Lock the second path; if this fails, release the first to avoid leak
        try {
            acquireWriteLock(second);
        } catch (FileOpException e) {
            releaseWriteLock(first);
            throw e;
        }
    }

    /**
     * Releases write locks on two paths (order does not matter for release).
     *
     * @param path1 first path to unlock
     * @param path2 second path to unlock
     */
    public void releaseWriteLocks(String path1, String path2) {
        releaseWriteLock(path1);
        releaseWriteLock(path2);
    }
}
