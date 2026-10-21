package remotefs.server;

import remotefs.common.FileOpException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AuthManager — handles user authentication and session management.
 *
 * AUTHENTICATION DESIGN (viva talking point):
 *
 *   1. PASSWORDS ARE NEVER STORED IN PLAINTEXT. We store a salted SHA-256
 *      hash. The format in users.properties is:
 *        username=saltHex:hashHex
 *
 *   2. A SALT is a random value prepended to the password before hashing.
 *      Why? Without a salt, two users with the same password would have the
 *      same hash, and an attacker could use a precomputed "rainbow table"
 *      to look up passwords. The salt makes each hash unique even for
 *      identical passwords.
 *
 *   3. On login, we:
 *        a. Look up the user's salt and stored hash
 *        b. Compute SHA-256(salt + providedPassword)
 *        c. Compare with the stored hash (constant-time comparison)
 *        d. If match → generate a random UUID session token
 *
 *   4. SESSION TOKENS are random UUIDs stored in a ConcurrentHashMap.
 *      Every remote call passes the token, and validateToken() checks
 *      that it exists and hasn't expired. Tokens expire after 30 minutes
 *      of inactivity (last-access time is updated on each valid call).
 *
 *   NOTE: For a production system, you'd use bcrypt/scrypt/Argon2 instead
 *   of SHA-256 (they are deliberately slow to resist brute-force attacks).
 *   SHA-256 is used here because the requirement is "no external frameworks".
 */
public class AuthManager {

    /** Session timeout: 30 minutes of inactivity. */
    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000;

    /** Salt length in bytes (128 bits = 16 bytes). */
    private static final int SALT_LENGTH = 16;

    /**
     * Loaded user credentials. Key = username, Value = "saltHex:hashHex".
     * Loaded once at startup from users.properties.
     */
    private final Properties userCredentials = new Properties();

    /**
     * Active sessions. Key = session token (UUID string).
     * ConcurrentHashMap for thread-safe access from multiple RMI threads.
     */
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /** Path to the users.properties file. */
    private final Path usersFile;

    /**
     * Represents an active login session.
     * Tracks the user and when they last accessed the system.
     */
    private static class Session {
        final String username;
        final Instant createdAt;
        /** Volatile because it is read/written by different RMI threads. */
        volatile Instant lastAccessedAt;

        Session(String username) {
            this.username = username;
            this.createdAt = Instant.now();
            this.lastAccessedAt = Instant.now();
        }
    }

    /**
     * Creates the AuthManager and loads user credentials from users.properties.
     * If the file doesn't exist, creates it with default test users.
     *
     * @param storageRoot the server's storage root directory
     */
    public AuthManager(Path storageRoot) {
        this.usersFile = storageRoot.resolve("users.properties");

        if (!Files.exists(usersFile)) {
            createDefaultUsers();
        }
        loadUsers();
    }

    // =====================================================================
    //  Login: verify credentials and issue a session token
    // =====================================================================

    /**
     * Authenticates a user and returns a session token.
     *
     * @param user     the username
     * @param password the plaintext password
     * @return a random UUID session token
     * @throws FileOpException if the username is unknown or the password is wrong
     */
    public String login(String user, String password) throws FileOpException {
        if (user == null || user.isBlank()) {
            throw new FileOpException("Username cannot be empty");
        }
        if (password == null) {
            throw new FileOpException("Password cannot be null");
        }

        // --- Look up stored credentials ---
        String storedEntry = userCredentials.getProperty(user);
        if (storedEntry == null) {
            throw new FileOpException("Invalid username or password");
        }

        // --- Parse salt:hash ---
        String[] parts = storedEntry.split(":");
        if (parts.length != 2) {
            throw new FileOpException("Corrupt user entry for: " + user);
        }
        String saltHex = parts[0];
        String storedHashHex = parts[1];

        // --- Recompute hash with the provided password ---
        byte[] salt = HexFormat.of().parseHex(saltHex);
        String computedHashHex = hashPassword(password, salt);

        // --- Compare hashes ---
        // We use MessageDigest.isEqual() for constant-time comparison.
        // A naive equals() would return early on the first mismatched byte,
        // leaking information about how many bytes matched (timing attack).
        if (!MessageDigest.isEqual(
                computedHashHex.getBytes(StandardCharsets.UTF_8),
                storedHashHex.getBytes(StandardCharsets.UTF_8))) {
            throw new FileOpException("Invalid username or password");
        }

        // --- Credentials valid → create session ---
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(user));
        System.out.println("[AuthManager] User '" + user + "' logged in, token: " + token);
        return token;
    }

    // =====================================================================
    //  Token validation: called at the start of every remote method
    // =====================================================================

    /**
     * Validates a session token and returns the associated username.
     * Also refreshes the session's last-access time to prevent timeout.
     *
     * This is called at the top of every FileServiceImpl method (except login).
     *
     * @param token the session token to validate
     * @return the username associated with this token
     * @throws FileOpException if the token is null, unknown, or expired
     */
    public String validateToken(String token) throws FileOpException {
        if (token == null || token.isBlank()) {
            throw new FileOpException("Authentication required: no session token provided");
        }

        Session session = sessions.get(token);
        if (session == null) {
            throw new FileOpException("Invalid session token: please login again");
        }

        // --- Check for expiry ---
        long idleMs = System.currentTimeMillis() - session.lastAccessedAt.toEpochMilli();
        if (idleMs > SESSION_TIMEOUT_MS) {
            // Lazy cleanup: remove expired session when we detect it
            sessions.remove(token);
            throw new FileOpException("Session expired after 30 minutes of inactivity: please login again");
        }

        // --- Refresh last-access time ---
        // This extends the session by another 30 minutes from now.
        session.lastAccessedAt = Instant.now();

        return session.username;
    }

    // =====================================================================
    //  Password hashing: SHA-256 with salt
    // =====================================================================

    /**
     * Computes SHA-256(salt + password) and returns the hash as a hex string.
     *
     * HOW SALTED HASHING WORKS (viva talking point):
     *   1. Concatenate the random salt bytes with the password bytes
     *   2. Feed the combined bytes into SHA-256
     *   3. SHA-256 produces a fixed 256-bit (32-byte) hash
     *   4. Convert the hash bytes to a hex string for storage
     *
     *   The salt ensures that even identical passwords produce different hashes,
     *   defeating rainbow-table attacks.
     *
     * @param password the plaintext password
     * @param salt     the random salt bytes
     * @return the hash as a lowercase hex string
     */
    private String hashPassword(String password, byte[] salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(password.getBytes(StandardCharsets.UTF_8));
            byte[] hashBytes = md.digest();
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in every JVM
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Generates a random salt and hashes the password.
     *
     * @param password the plaintext password
     * @return "saltHex:hashHex" string for storage in users.properties
     */
    private String createPasswordEntry(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);
        String hashHex = hashPassword(password, salt);
        return saltHex + ":" + hashHex;
    }

    // =====================================================================
    //  User file management
    // =====================================================================

    /**
     * Creates a default users.properties file with test users.
     * Called on first run when no user file exists.
     */
    private void createDefaultUsers() {
        Properties defaults = new Properties();
        defaults.setProperty("admin", createPasswordEntry("admin123"));
        defaults.setProperty("alice", createPasswordEntry("password1"));
        defaults.setProperty("bob", createPasswordEntry("password2"));

        try (var writer = Files.newBufferedWriter(usersFile)) {
            defaults.store(writer, "RemoteFS user credentials (saltHex:hashHex)");
        } catch (IOException e) {
            System.err.println("[AuthManager] Warning: could not create default users file: " + e.getMessage());
            return;
        }

        System.out.println("[AuthManager] Created default users.properties with test users:");
        System.out.println("  admin / admin123");
        System.out.println("  alice / password1");
        System.out.println("  bob   / password2");
    }

    /**
     * Loads user credentials from users.properties into memory.
     */
    private void loadUsers() {
        if (!Files.exists(usersFile)) {
            System.err.println("[AuthManager] Warning: users.properties not found");
            return;
        }
        try (var reader = Files.newBufferedReader(usersFile)) {
            userCredentials.load(reader);
            System.out.println("[AuthManager] Loaded " + userCredentials.size() + " user(s).");
        } catch (IOException e) {
            System.err.println("[AuthManager] Warning: could not load users file: " + e.getMessage());
        }
    }
}
