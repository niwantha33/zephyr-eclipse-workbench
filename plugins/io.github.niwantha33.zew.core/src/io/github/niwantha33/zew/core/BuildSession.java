// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds the mutable West environment stable through configure, compile and verification. */
public final class BuildSession implements AutoCloseable {
    private static final class Busy extends IOException {
        Busy(String message) { super(message); }
    }
    private final FileChannel channel;
    private final FileLock lock;
    private BuildSession(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
    public static BuildSession acquire(Path root) throws IOException {
        Path path = root.resolve(".zew-build-session.lock");
        if (Files.isSymbolicLink(path)) throw new IOException("Build session lock must not be a symlink.");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new Busy("Another project is building in this shared Zephyr environment. Retry when it finishes.");
            return new BuildSession(channel, lock);
        } catch (OverlappingFileLockException error) {
            channel.close();
            throw new Busy("Shared Zephyr environment is busy. Retry after the other build finishes.");
        } catch (IOException error) {
            channel.close(); throw error;
        }
    }
    public static BuildSession acquire(Path root, java.util.function.BooleanSupplier cancelled,
            java.time.Duration timeout, java.util.function.Consumer<String> progress) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean announced = false;
        while (true) {
            if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Build cancelled while waiting for shared environment.");
            try { return acquire(root); }
            catch (Busy busy) {
                if (Files.isSymbolicLink(root.resolve(".zew-build-session.lock")) || System.nanoTime() >= deadline)
                    throw busy;
                if (!announced) { progress.accept("Waiting for another project using this shared Zephyr environment..."); announced = true; }
                Thread.sleep(200);
            }
        }
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
