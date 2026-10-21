package remotefs.common;

/**
 * FileOpException — a custom checked exception for all file operation errors.
 *
 * DESIGN DECISIONS (viva talking point):
 *   1. CHECKED exception (extends Exception, not RuntimeException).
 *      This forces every caller to explicitly handle or propagate it,
 *      which is appropriate for expected error conditions like
 *      "file not found" or "version conflict".
 *
 *   2. SECURITY: we override fillInStackTrace() to return an empty trace.
 *      When this exception is serialised and sent to the client via RMI,
 *      the client must NOT see the server's internal class names, method
 *      names, or line numbers — that would leak implementation details
 *      that could aid an attacker. The client only sees the message string.
 *
 *   3. Implements Serializable (inherited from Exception → Throwable),
 *      which is required because RMI serialises exceptions to send them
 *      back to the client JVM.
 */
public class FileOpException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a FileOpException with a user-friendly error message.
     * The message should describe what went wrong in plain English,
     * without mentioning internal class names or file system paths
     * outside the storage root.
     *
     * @param message human-readable error description
     */
    public FileOpException(String message) {
        super(message);
    }

    /**
     * Prevents the JVM from capturing the server-side stack trace.
     *
     * WHY? (viva talking point)
     *   By default, when you throw an exception, Java records the full
     *   call stack (class names, method names, line numbers). Because RMI
     *   serialises the entire exception object — including its stack trace —
     *   this information would travel to the client, revealing the server's
     *   internal structure. Returning 'this' without filling in the trace
     *   prevents that information leak.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;  // deliberately empty — no stack trace captured
    }
}
