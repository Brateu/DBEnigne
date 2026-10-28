package db.storage;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class StorageManager implements Closeable {

    private final Path dataDir;
    private final Map<Integer, DiskManager> files;

    public StorageManager(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir must not be null");
        this.dataDir = dataDir;
        this.files = new HashMap<>();
    }

    public DiskManager open(int fileId) throws IOException {
        if (fileId < 0) {
            throw new IllegalArgumentException("fileId must be non-negative, got " + fileId);
        }
        if (files.containsKey(fileId)) {
            return files.get(fileId);
        } else {
            DiskManager diskManager = new DiskManager(dataDir.resolve(String.valueOf(fileId)));
            files.put(fileId, diskManager);
            return diskManager;
        }
    }

    public DiskManager get(int fileId) {
        if (fileId < 0) {
            throw new IllegalArgumentException("fileId must be non-negative, got " + fileId);
        }
        if (files.containsKey(fileId)) {
            return files.get(fileId);
        } else {
            throw new IllegalArgumentException("file " + fileId + " is not open");
        }
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (DiskManager disks : files.values()) {
            try {
                disks.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        files.clear();
        if (first != null) {
            throw first;
        }
    }
}