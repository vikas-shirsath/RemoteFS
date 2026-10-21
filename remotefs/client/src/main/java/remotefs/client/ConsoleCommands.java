package remotefs.client;

import remotefs.common.FileInfo;
import remotefs.common.FileOpException;
import remotefs.common.FileService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.util.List;

/**
 * ConsoleCommands — maps CLI verbs to FileService remote calls.
 *
 * Each method handles one CLI command:
 *   - Parses the command arguments
 *   - Calls the appropriate FileService method via RMI
 *   - Pretty-prints the result or error
 *
 * All RemoteException and FileOpException are caught and displayed as
 * human-readable messages — the user never sees raw stack traces.
 */
public class ConsoleCommands {

    /** The RMI stub — all method calls are forwarded over the network. */
    private final FileService service;

    /** The current session token (null until login succeeds). */
    private String token;

    public ConsoleCommands(FileService service) {
        this.service = service;
    }

    /**
     * Returns true if the user has logged in (has a valid token).
     */
    public boolean isLoggedIn() {
        return token != null;
    }

    // =====================================================================
    //  login <user> <password>
    // =====================================================================

    /**
     * Authenticates with the server and stores the session token.
     */
    public void login(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: login <username> <password>");
            return;
        }
        try {
            token = service.login(args[0], args[1]);
            System.out.println("✓ Logged in as '" + args[0] + "'");
        } catch (FileOpException e) {
            System.out.println("✗ Login failed: " + e.getMessage());
        } catch (RemoteException e) {
            System.out.println("✗ Connection error: " + cleanRemoteError(e));
        }
    }

    // =====================================================================
    //  ls [directory]
    // =====================================================================

    /**
     * Lists the contents of a directory (default: storage root).
     */
    public void ls(String[] args) {
        if (!requireLogin()) return;
        String dir = args.length > 0 ? args[0] : "";
        try {
            List<FileInfo> entries = service.list(token, dir);
            if (entries.isEmpty()) {
                System.out.println("(empty directory)");
            } else {
                System.out.println(String.format("%-7s %-30s %12s  %-6s  %s",
                        "Type", "Name", "Size", "Ver", "Last Modified"));
                System.out.println("-".repeat(85));
                for (FileInfo fi : entries) {
                    System.out.println(fi);
                }
                System.out.println("\n" + entries.size() + " item(s)");
            }
        } catch (FileOpException e) {
            System.out.println("✗ " + e.getMessage());
        } catch (RemoteException e) {
            System.out.println("✗ Connection error: " + cleanRemoteError(e));
        }
    }

    // =====================================================================
    //  cat <path>
    // =====================================================================

    /**
     * Reads and displays the contents of a remote file.
     */
    public void cat(String[] args) {
        if (!requireLogin()) return;
        if (args.length < 1) {
            System.out.println("Usage: cat <path>");
            return;
        }
        try {
            byte[] data = service.read(token, args[0]);
            // Display as text (UTF-8). For binary files this may look garbled,
            // but cat is typically used for text files.
            System.out.println(new String(data, StandardCharsets.UTF_8));
        } catch (FileOpException e) {
            System.out.println("✗ " + e.getMessage());
        } catch (RemoteException e) {
            System.out.println("✗ Connection error: " + cleanRemoteError(e));
        }
    }

    // =====================================================================
    //  put <local-file> <remote-path>
    // =====================================================================

    /**
     * Uploads a local file to the server.
     * Reads the current version via stat() first, then writes with that version.
     * If the file is new, uses expectedVersion=0.
     */
    public void put(String[] args) {
        if (!requireLogin()) return;
        if (args.length < 2) {
            System.out.println("Usage: put <local-file> <remote-path>");
            return;
        }

        Path localPath = Path.of(args[0]);
        String remotePath = args[1];

        if (!Files.exists(localPath)) {
            System.out.println("✗ Local file not found: " + args[0]);
            return;
        }

        try {
            byte[] data = Files.readAllBytes(localPath);

            // Get the current version (0 if the file doesn't exist on the server)
            int expectedVersion = 0;
            try {
                FileInfo info = service.stat(token, remotePath);
                expectedVersion = info.getVersion();
            } catch (FileOpException e) {
                // File doesn't exist yet — expectedVersion stays 0
            }

            int newVersion = service.write(token, remotePath, data, expectedVersion);
            System.out.println("✓ Uploaded " + localPath.getFileName()
                    + " → " + remotePath + " (v" + newVersion
                    + ", " + data.length + " bytes)");

        } catch (FileOpException e) {
            System.out.println("✗ " + e.getMessage());
        } catch (RemoteException e) {
            System.out.println("✗ Connection error: " + cleanRemoteError(e));
        } catch (IOException e) {
            System.out.println("✗ Failed to read local file: " + e.getMessage());
        }
    }

    // =====================================================================
    //  get <remote-path> <local-file>
    // =====================================================================

    /**
     * Downloads a remote file to a local path.
     */
    public void get(String[] args) {
        if (!requireLogin()) return;
        if (args.length < 2) {
            System.out.println("Usage: get <remote-path> <local-file>");
            return;
        }

        String remotePath = args[0];
        Path localPath = Path.of(args[1]);

        try {
            byte[] data = service.read(token, remotePath);
            Files.write(localPath, data);
            System.out.println("✓ Downloaded " + remotePath
                    + " → " + localPath.getFileName()
                    + " (" + data.length + " bytes)");

        } catch (FileOpException e) {
            System.out.println("✗ " + e.getMessage());
        } catch (RemoteException e) {
            System.out.println("✗ Connection error: " + cleanRemoteError(e));
        } catch (IOException e) {
            System.out.println("✗ Failed to write local file: " + e.getMessage());
        }
    }

    // =====================================================================
    //  append <path> <text>
    // =====================================================================

    /**
     * Appends text to a remote file.
     */
    public void append(String[] args) {
        if (!requireLogin()) return;
        if (args.length < 2) {
            System.out.println("Usage: append <path> <text>");
            return;
        }

        String path = args[0];
        // Join remaining args as the text to append (allows spaces)
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) sb.append(" ");
            sb.append(args[i]);
        }
        sb.append("\n"); // Append a newline for convenience

        try {
            service.append(token, path, sb.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("✓ Appended to " + path);
        } catch (FileOpException e) {
            System.out.println("✗ " + e.getMessage());
        } catch (RemoteException e) {
            System.out.println("✗ Connection error: " + cleanRemoteError(e));
        }
    }

    // =====================================================================
    //  rm <path>
    // =====================================================================

    /**
     * Deletes a remote file or empty directory.
     */
    public void rm(String[] args) {
        if (!requireLogin()) return;
        if (args.length < 1) {
            System.out.println("Usage: rm <path>");
            return;
        }
        try {
            service.delete(token, args[0]);
            System.out.println("✓ Deleted " + args[0]);
        } catch (FileOpException e) {
            System.out.println("✗ " + e.getMessage());
        } catch (RemoteException e) {
            System.out.println("✗ Connection error: " + cleanRemoteError(e));
        }
    }

    // =====================================================================
    //  mv <from> <to>
    // =====================================================================

    /**
     * Renames / moves a remote file or directory.
     */
    public void mv(String[] args) {
        if (!requireLogin()) return;
        if (args.length < 2) {
            System.out.println("Usage: mv <from> <to>");
            return;
        }
        try {
            service.rename(token, args[0], args[1]);
            System.out.println("✓ Renamed " + args[0] + " → " + args[1]);
        } catch (FileOpException e) {
            System.out.println("✗ " + e.getMessage());
        } catch (RemoteException e) {
            System.out.println("✗ Connection error: " + cleanRemoteError(e));
        }
    }

    // =====================================================================
    //  mkdir <path>
    // =====================================================================

    /**
     * Creates a remote directory (and any missing parents).
     */
    public void mkdir(String[] args) {
        if (!requireLogin()) return;
        if (args.length < 1) {
            System.out.println("Usage: mkdir <path>");
            return;
        }
        try {
            service.mkdir(token, args[0]);
            System.out.println("✓ Created directory " + args[0]);
        } catch (FileOpException e) {
            System.out.println("✗ " + e.getMessage());
        } catch (RemoteException e) {
            System.out.println("✗ Connection error: " + cleanRemoteError(e));
        }
    }

    // =====================================================================
    //  stat <path>
    // =====================================================================

    /**
     * Shows metadata about a remote file or directory.
     */
    public void stat(String[] args) {
        if (!requireLogin()) return;
        if (args.length < 1) {
            System.out.println("Usage: stat <path>");
            return;
        }
        try {
            FileInfo info = service.stat(token, args[0]);
            System.out.println("  Name:          " + info.getName());
            System.out.println("  Type:          " + (info.isDirectory() ? "Directory" : "File"));
            System.out.println("  Size:          " + String.format("%,d bytes", info.getSize()));
            System.out.println("  Version:       " + info.getVersion());
            System.out.println("  Last Modified: " + java.time.Instant.ofEpochMilli(info.getLastModified()));
        } catch (FileOpException e) {
            System.out.println("✗ " + e.getMessage());
        } catch (RemoteException e) {
            System.out.println("✗ Connection error: " + cleanRemoteError(e));
        }
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    /**
     * Checks that the user has logged in. Prints a message if not.
     *
     * @return true if logged in, false otherwise
     */
    private boolean requireLogin() {
        if (token == null) {
            System.out.println("✗ Not logged in. Use: login <username> <password>");
            return false;
        }
        return true;
    }

    /**
     * Extracts a user-friendly error message from a RemoteException.
     * RemoteException messages can be long and technical; this trims them.
     */
    private String cleanRemoteError(RemoteException e) {
        String msg = e.getMessage();
        if (msg == null) return "unknown RMI error";
        // Trim to the first line for readability
        int newline = msg.indexOf('\n');
        return newline > 0 ? msg.substring(0, newline) : msg;
    }

    /**
     * Prints the help text listing all available commands.
     */
    public void printHelp() {
        System.out.println("""
                Available commands:
                  login <user> <password>    Log in to the server
                  ls [directory]             List directory contents
                  cat <path>                 Display file contents
                  put <local> <remote>       Upload a local file
                  get <remote> <local>       Download a remote file
                  append <path> <text>       Append text to a file
                  rm <path>                  Delete a file or empty directory
                  mv <from> <to>             Rename / move a file
                  mkdir <path>               Create a directory
                  stat <path>                Show file metadata
                  help                       Show this help
                  exit                       Quit the client
                """);
    }
}
