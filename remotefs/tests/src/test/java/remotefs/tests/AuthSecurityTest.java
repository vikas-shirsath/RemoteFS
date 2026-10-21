package remotefs.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import remotefs.common.FileOpException;
import remotefs.common.FileService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthSecurityTest — verifies authentication and authorisation controls.
 *
 * SCENARIOS:
 *   1. Operations with no token → rejected
 *   2. Operations with an invalid/made-up token → rejected
 *   3. Login with wrong password → rejected
 *   4. Login with non-existent user → rejected
 *   5. Valid login → succeeds, token works for operations
 *
 * WHY THIS MATTERS (viva talking point):
 *   Without token validation, any client could access any file without
 *   logging in. The AuthManager ensures that every remote call (except
 *   login) carries a valid session token that maps to an authenticated user.
 */
class AuthSecurityTest {

    @TempDir
    static Path tempDir;

    static FileService service;

    @BeforeAll
    static void setUp() throws Exception {
        service = TestHelper.startServer(tempDir);
    }

    @Test
    void operationWithNoToken_shouldFail() {
        FileOpException ex = assertThrows(FileOpException.class, () -> {
            service.list(null, "");
        });
        assertTrue(ex.getMessage().toLowerCase().contains("authentication")
                || ex.getMessage().toLowerCase().contains("token"),
                "Error should mention authentication or token");
        System.out.println("  ✓ No token rejected: " + ex.getMessage());
    }

    @Test
    void operationWithInvalidToken_shouldFail() {
        FileOpException ex = assertThrows(FileOpException.class, () -> {
            service.list("totally-fake-token-12345", "");
        });
        assertTrue(ex.getMessage().toLowerCase().contains("invalid")
                || ex.getMessage().toLowerCase().contains("token"),
                "Error should mention invalid token");
        System.out.println("  ✓ Invalid token rejected: " + ex.getMessage());
    }

    @Test
    void operationWithEmptyToken_shouldFail() {
        FileOpException ex = assertThrows(FileOpException.class, () -> {
            service.read("", "somefile.txt");
        });
        System.out.println("  ✓ Empty token rejected: " + ex.getMessage());
    }

    @Test
    void loginWithWrongPassword_shouldFail() {
        FileOpException ex = assertThrows(FileOpException.class, () -> {
            service.login("admin", "wrongpassword");
        });
        // Error message should NOT reveal whether the username or password was wrong
        // (to prevent user enumeration attacks)
        assertTrue(ex.getMessage().contains("Invalid username or password"),
                "Error should not reveal which credential was wrong");
        System.out.println("  ✓ Wrong password rejected: " + ex.getMessage());
    }

    @Test
    void loginWithNonExistentUser_shouldFail() {
        FileOpException ex = assertThrows(FileOpException.class, () -> {
            service.login("nonexistent_user_xyz", "anypassword");
        });
        // Same generic error message — doesn't reveal whether user exists
        assertTrue(ex.getMessage().contains("Invalid username or password"),
                "Error should not reveal whether user exists");
        System.out.println("  ✓ Non-existent user rejected: " + ex.getMessage());
    }

    @Test
    void loginWithEmptyUsername_shouldFail() {
        assertThrows(FileOpException.class, () -> {
            service.login("", "password");
        });
    }

    @Test
    void validLogin_shouldReturnTokenThatWorks() throws Exception {
        // Login with valid credentials
        String token = service.login("admin", "admin123");
        assertNotNull(token, "Token should not be null");
        assertFalse(token.isBlank(), "Token should not be blank");

        // Token should work for operations
        service.write(token, "auth_test.txt",
                "test content".getBytes(StandardCharsets.UTF_8), 0);

        byte[] data = service.read(token, "auth_test.txt");
        assertEquals("test content", new String(data, StandardCharsets.UTF_8));

        System.out.println("  ✓ Valid login and token usage works");
    }

    @Test
    void differentLoginsGetDifferentTokens() throws Exception {
        String token1 = service.login("admin", "admin123");
        String token2 = service.login("alice", "password1");

        assertNotEquals(token1, token2,
                "Different users should get different tokens");

        // Both tokens should be independently valid
        service.list(token1, "");
        service.list(token2, "");

        System.out.println("  ✓ Different users get unique tokens");
    }
}
