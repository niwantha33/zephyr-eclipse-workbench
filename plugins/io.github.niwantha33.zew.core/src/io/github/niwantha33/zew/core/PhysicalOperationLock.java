// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

/** Conservative machine-user lock shared by cooperating Workbench instances, not other IDEs. */
public final class PhysicalOperationLock implements AutoCloseable {
    private static final java.util.Set<Path> HELD = new java.util.HashSet<>();
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PhysicalOperationLock(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    public static synchronized PhysicalOperationLock acquire(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.toRealPath().resolve("physical-operation.lock");
        // On POSIX, closing any descriptor for a file can release this process's
        // locks. Reject overlap before opening a second descriptor for that file.
        if (HELD.contains(file)) throw busy();
        if (Files.isSymbolicLink(file)) throw new IOException("Hardware operation lock must not be a symbolic link.");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw busy();
            HELD.add(file);
            return new PhysicalOperationLock(file, channel, lock);
        } catch (OverlappingFileLockException error) {
            channel.close();
            throw busy();
        } catch (IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
    }

    private static IOException busy() {
        return new IOException("Another Workbench Flash or Debug Server operation owns the hardware interface. "
                + "Terminate it before retrying. This lock coordinates Workbench instances only; "
                + "close other IDE/probe tools separately.");
    }

    @Override public void close() throws IOException {
        synchronized (PhysicalOperationLock.class) {
            if (closed.compareAndSet(false, true)) {
                try { lock.release(); }
                finally {
                    try { channel.close(); }
                    finally { HELD.remove(path); }
                }
            }
        }
        // Never unlink a lock file: another process may already hold its inode.
    }
}
