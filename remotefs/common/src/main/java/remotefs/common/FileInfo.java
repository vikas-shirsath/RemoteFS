package remotefs.common;

import java.io.Serializable;

/**
 * FileInfo — an immutable data-transfer object (DTO) carrying file metadata.
 *
 * WHY Serializable? (viva talking point)
 *   RMI needs to send this object from the server JVM to the client JVM
 *   over the network. Java serialisation converts the object into a byte
 *   stream on the server side, transmits it, and reconstructs the object
 *   on the client side. Any class sent across RMI must implement
 *   java.io.Serializable — otherwise you get a NotSerializableException.
 *
 *   The serialVersionUID is a version stamp. If the server and client have
 *   different versions of this class, the UID mismatch prevents silent
 *   data corruption by throwing InvalidClassException.
 */
public class FileInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;          // file or directory name (not full path)
    private final long size;            // size in bytes (0 for directories)
    private final boolean isDirectory;  // true if this entry is a directory
    private final long lastModified;    // epoch millis of last modification
    private final int version;          // version counter (0 for dirs / new files)

    /**
     * Constructs a FileInfo with all fields.
     */
    public FileInfo(String name, long size, boolean isDirectory,
                    long lastModified, int version) {
        this.name = name;
        this.size = size;
        this.isDirectory = isDirectory;
        this.lastModified = lastModified;
        this.version = version;
    }

    public String getName()       { return name; }
    public long getSize()         { return size; }
    public boolean isDirectory()  { return isDirectory; }
    public long getLastModified() { return lastModified; }
    public int getVersion()       { return version; }

    /**
     * Human-readable representation used by the client's "ls" and "stat" commands.
     * Example:  [DIR]  documents/                 0 B   v0   2024-01-15 10:30:00
     *           [FILE] readme.txt             1,024 B   v3   2024-01-15 11:45:22
     */
    @Override
    public String toString() {
        String type = isDirectory ? "[DIR]  " : "[FILE] ";
        String displayName = isDirectory ? name + "/" : name;
        return String.format("%-7s %-30s %,10d B   v%-4d  %s",
                type, displayName, size, version,
                java.time.Instant.ofEpochMilli(lastModified));
    }
}
