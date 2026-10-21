package remotefs.client;

import remotefs.common.FileService;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Arrays;
import java.util.Scanner;

/**
 * ClientMain — the entry point for the RemoteFS console client.
 *
 * HOW THE CLIENT CONNECTS (viva talking point):
 *   1. The client looks up the RMI registry at the server's host and port.
 *      LocateRegistry.getRegistry(host, port) returns a reference to
 *      the remote registry.
 *
 *   2. registry.lookup("FileService") asks the registry for the stub
 *      bound under the name "FileService". The registry serialises the
 *      stub and sends it to the client.
 *
 *   3. The stub is a local proxy that implements the FileService interface.
 *      Every method call on the stub is transparently forwarded over TCP
 *      to the server's FileServiceImpl.
 *
 *   4. The client then enters a REPL (read-eval-print loop) where it reads
 *      commands from stdin and dispatches them to ConsoleCommands, which
 *      calls the appropriate stub methods.
 *
 * USAGE:
 *   mvn exec:java -pl client                                    # localhost:1099
 *   mvn exec:java -pl client -Dexec.args="--host 192.168.1.100" # remote host
 *   mvn exec:java -pl client -Dexec.args="--host myserver --port 1099"
 */
public class ClientMain {

    /** Default server host. */
    private static final String DEFAULT_HOST = "localhost";

    /** Default RMI registry port (must match the server). */
    private static final int DEFAULT_PORT = 1099;

    /** The name under which the server registered the remote object. */
    private static final String SERVICE_NAME = "FileService";

    public static void main(String[] args) {
        // --- Parse command-line arguments ---
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host", "-h" -> {
                    if (i + 1 < args.length) host = args[++i];
                }
                case "--port", "-p" -> {
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                }
                case "--help" -> {
                    System.out.println("Usage: remotefs-client [--host <host>] [--port <port>]");
                    System.exit(0);
                }
            }
        }

        System.out.println("==============================================");
        System.out.println("  RemoteFS Client");
        System.out.println("  Connecting to " + host + ":" + port);
        System.out.println("==============================================");

        try {
            // --- Step 1: Get a reference to the remote RMI registry ---
            // This does NOT create a registry — it creates a stub that points
            // to an existing registry at the specified host and port.
            Registry registry = LocateRegistry.getRegistry(host, port);

            // --- Step 2: Look up the FileService remote object ---
            // The registry returns a stub (a proxy) that implements FileService.
            // Every method call on this stub is forwarded over the network.
            FileService service = (FileService) registry.lookup(SERVICE_NAME);

            System.out.println("✓ Connected to RemoteFS server");
            System.out.println("  Type 'help' for available commands, 'exit' to quit.\n");

            // --- Step 3: Enter the REPL (Read-Eval-Print Loop) ---
            ConsoleCommands commands = new ConsoleCommands(service);
            repl(commands);

        } catch (Exception e) {
            System.err.println("✗ Failed to connect to server at " + host + ":" + port);
            System.err.println("  Ensure the server is running and the host/port are correct.");
            System.err.println("  Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * The Read-Eval-Print Loop. Reads lines from stdin, parses them into
     * a command name and arguments, and dispatches to ConsoleCommands.
     */
    private static void repl(ConsoleCommands commands) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            // Print a prompt that shows login status
            String prompt = commands.isLoggedIn() ? "remotefs> " : "remotefs (not logged in)> ";
            System.out.print(prompt);

            if (!scanner.hasNextLine()) {
                break; // EOF (Ctrl+D / Ctrl+Z)
            }

            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            // Split the line into command and arguments.
            // The first token is the command name; the rest are arguments.
            String[] parts = line.split("\\s+");
            String command = parts[0].toLowerCase();
            String[] cmdArgs = Arrays.copyOfRange(parts, 1, parts.length);

            // --- Dispatch to the appropriate command handler ---
            switch (command) {
                case "login"  -> commands.login(cmdArgs);
                case "ls"     -> commands.ls(cmdArgs);
                case "cat"    -> commands.cat(cmdArgs);
                case "put"    -> commands.put(cmdArgs);
                case "get"    -> commands.get(cmdArgs);
                case "append" -> commands.append(cmdArgs);
                case "rm"     -> commands.rm(cmdArgs);
                case "mv"     -> commands.mv(cmdArgs);
                case "mkdir"  -> commands.mkdir(cmdArgs);
                case "stat"   -> commands.stat(cmdArgs);
                case "help"   -> commands.printHelp();
                case "exit", "quit" -> {
                    System.out.println("Goodbye!");
                    return;
                }
                default -> System.out.println("Unknown command: " + command
                        + ". Type 'help' for available commands.");
            }
        }
    }
}
