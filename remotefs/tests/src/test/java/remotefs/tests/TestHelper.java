package remotefs.tests;

import remotefs.common.FileService;
import remotefs.server.FileServiceImpl;
import remotefs.server.ServerMain;

import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/**
 * TestHelper — shared utilities for all test classes.
 *
 * Provides methods to start an embedded RMI server in-process so that
 * tests do not require a separate server process.
 */
public class TestHelper {

    /** Port offset to avoid conflicts with a running server. */
    private static final int TEST_PORT = 11099;

    private static Registry registry;
    private static FileServiceImpl service;

    /**
     * Starts an embedded RMI server for testing.
     * Creates a fresh RMI registry and FileServiceImpl bound to it.
     *
     * @param storageRoot the temp directory to use as storage root
     * @return the FileService stub for making remote calls
     */
    public static synchronized FileService startServer(Path storageRoot) throws Exception {
        // Create a fresh registry (or reuse if already created on this port)
        try {
            registry = LocateRegistry.createRegistry(TEST_PORT);
        } catch (Exception e) {
            // Registry already exists from a previous test — reuse it
            registry = LocateRegistry.getRegistry(TEST_PORT);
        }

        // Create and bind the service
        service = new FileServiceImpl(storageRoot);
        registry.rebind(ServerMain.SERVICE_NAME, service);

        // Look up the stub (simulates what a real client would do)
        return (FileService) registry.lookup(ServerMain.SERVICE_NAME);
    }

    /**
     * Returns the underlying FileServiceImpl for direct inspection in tests.
     */
    public static FileServiceImpl getServiceImpl() {
        return service;
    }

    /**
     * Logs in a default test user and returns the session token.
     */
    public static String loginDefaultUser(FileService service) throws Exception {
        return service.login("admin", "admin123");
    }
}
