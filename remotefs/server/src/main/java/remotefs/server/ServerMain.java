package remotefs.server;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ServerMain — bootstrap class that starts the RMI registry, creates the
 * FileServiceImpl remote object, and binds it to the registry.
 *
 * HOW RMI BOOTSTRAPPING WORKS (viva talking point):
 *
 *   1. The RMI REGISTRY is a simple name-service that runs on a well-known
 *      port (default 1099). It maps string names to remote object stubs.
 *
 *   2. LocateRegistry.createRegistry(port) starts an in-process registry.
 *      Alternatively, you could start rmiregistry externally on the command
 *      line, but an in-process registry is simpler for a lab project.
 *
 *   3. We instantiate FileServiceImpl (which extends UnicastRemoteObject).
 *      The UnicastRemoteObject constructor exports the object — it opens
 *      a TCP socket and starts listening for incoming RMI calls.
 *
 *   4. registry.rebind("FileService", service) registers the object's stub
 *      under the name "FileService". Clients look up this name to get the
 *      stub, which they use to make remote calls.
 *
 *   5. The -Djava.rmi.server.hostname system property tells RMI which
 *      hostname/IP address to embed in the stub. Clients will connect to
 *      this address. On a LAN, set it to the server machine's IP address.
 *
 * USAGE:
 *   # Start the server with default settings:
 *   mvn exec:java -pl server
 *
 *   # Start with a custom hostname (for cross-machine access):
 *   mvn exec:java -pl server -Djava.rmi.server.hostname=192.168.1.100
 *
 *   # Start with a custom storage directory:
 *   mvn exec:java -pl server -Dremotefs.storage=my_data
 */
public class ServerMain {

    /** Default port for the RMI registry. */
    private static final int DEFAULT_PORT = 1099;

    /** Default storage directory (relative to working directory). */
    private static final String DEFAULT_STORAGE = "server_data";

    /** The name under which the remote object is registered in the RMI registry. */
    public static final String SERVICE_NAME = "FileService";

    public static void main(String[] args) {
        try {
            // --- 1. Read configuration from system properties ---
            int port = Integer.getInteger("remotefs.port", DEFAULT_PORT);
            String storageDirName = System.getProperty("remotefs.storage", DEFAULT_STORAGE);
            Path storageRoot = Paths.get(storageDirName).toAbsolutePath().normalize();

            // Log the hostname that RMI will advertise to clients.
            // If not set, RMI uses the local hostname/IP.
            String hostname = System.getProperty("java.rmi.server.hostname", "(auto-detect)");
            System.out.println("==============================================");
            System.out.println("  RemoteFS Server starting");
            System.out.println("  RMI Registry port : " + port);
            System.out.println("  Storage root      : " + storageRoot);
            System.out.println("  RMI hostname      : " + hostname);
            System.out.println("==============================================");

            // --- 2. Create the RMI registry ---
            // This starts a registry inside this JVM process.
            // External clients (or rmiregistry processes) connect to this port.
            Registry registry = LocateRegistry.createRegistry(port);
            System.out.println("[ServerMain] RMI registry created on port " + port);

            // --- 3. Create and export the remote object ---
            // The FileServiceImpl constructor calls super() which exports
            // the object and starts listening for incoming RMI calls.
            FileServiceImpl service = new FileServiceImpl(storageRoot);

            // --- 4. Bind the remote object to the registry ---
            // rebind() replaces any existing binding with the same name.
            // We use rebind instead of bind so that restarting the server
            // doesn't fail with AlreadyBoundException.
            registry.rebind(SERVICE_NAME, service);
            System.out.println("[ServerMain] FileService bound as '" + SERVICE_NAME + "'");

            System.out.println("[ServerMain] Server is ready. Waiting for client connections...");
            System.out.println("[ServerMain] Press Ctrl+C to shut down.");

            // Block the main thread forever so the JVM stays alive.
            // Without this, exec-maven-plugin exits after main() returns,
            // which kills the RMI server before any client can connect.
            // Thread.currentThread().join() waits indefinitely until the
            // thread is interrupted (which happens on Ctrl+C / SIGINT).
            Thread.currentThread().join();

        } catch (InterruptedException e) {
            System.out.println("[ServerMain] Server interrupted — shutting down.");
        } catch (Exception e) {
            System.err.println("[ServerMain] FATAL: Server failed to start.");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
