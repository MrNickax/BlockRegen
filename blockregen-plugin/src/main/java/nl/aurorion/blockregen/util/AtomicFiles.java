package nl.aurorion.blockregen.util;

import lombok.extern.java.Log;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Crash-safe file writes.
 * <p>
 * Data is written to a temporary file next to the target, forced to the disk and then moved over the target in one
 * step. A crash (power loss, kill -9) leaves either the old or the new file, never a truncated one.
 */
@Log
public final class AtomicFiles {

    // Symbolic links followed at most when resolving a target, like the usual OS limit.
    private static final int MAX_LINK_DEPTH = 40;

    private AtomicFiles() {
    }

    /**
     * Atomically replace the contents of {@code target} with {@code data}.
     * <p>
     * A symbolic link is written through (it keeps pointing at its file) and the replaced file's POSIX permissions
     * and group are kept.
     */
    public static void write(@NotNull Path target, @NotNull byte[] data) throws IOException {
        try (PendingWrite pending = prepare(target, data)) {
            pending.commit();
        }
    }

    /**
     * The first half of {@link #write}: the data is written and forced to the disk in a temporary file next to the
     * target. The target is only replaced by {@link PendingWrite#commit()}, closing without a commit discards it.
     */
    @NotNull
    public static PendingWrite prepare(@NotNull Path target, @NotNull byte[] data) throws IOException {
        // Replacing a symbolic link itself would detach it from the file it points to.
        Path resolved = resolve(target, 0);
        Path directory = resolved.getParent();
        Files.createDirectories(directory);

        // Not Files#createTempFile, its files are owner-only (0600) on POSIX. A plain new file gets the usual umask.
        Path temp = directory.resolve(resolved.getFileName() + "." + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp");

        // Fails without creating anything if the name is taken, the delete below only ever removes our own file.
        FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            try (FileChannel output = channel) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }

            copyPermissions(resolved, temp);
            return new PendingWrite(temp, resolved);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                t.addSuppressed(e);
            }
            throw t;
        }
    }

    // Where a write to target ends up: links are followed, a dangling one too.
    @NotNull
    private static Path resolve(@NotNull Path target, int depth) throws IOException {
        Path absolute = target.toAbsolutePath();

        if (Files.exists(absolute)) {
            return absolute.toRealPath();
        }

        if (Files.isSymbolicLink(absolute)) {
            if (depth >= MAX_LINK_DEPTH) {
                throw new FileSystemException(target.toString(), null, "Too many levels of symbolic links");
            }
            return resolve(absolute.resolveSibling(Files.readSymbolicLink(absolute)), depth + 1);
        }

        return absolute;
    }

    // Give the new file the permissions and group (when allowed) of the file it replaces. POSIX file systems only.
    private static void copyPermissions(@NotNull Path from, @NotNull Path to) {
        PosixFileAttributeView fromView = Files.getFileAttributeView(from, PosixFileAttributeView.class);
        PosixFileAttributeView toView = Files.getFileAttributeView(to, PosixFileAttributeView.class);

        if (fromView == null || toView == null || !Files.exists(from)) {
            return;
        }

        PosixFileAttributes attributes;
        try {
            attributes = fromView.readAttributes();
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not read the permissions of " + from + ": " + e.getMessage(), e);
            return;
        }

        // Before the permissions, changing the group can clear some of them.
        try {
            toView.setGroup(attributes.group());
        } catch (IOException | SecurityException e) {
            // Not a member of that group, the file keeps the default one.
            log.fine(() -> "Could not keep the group of " + from + ": " + e.getMessage());
        }

        try {
            toView.setPermissions(attributes.permissions());
        } catch (IOException | SecurityException e) {
            log.log(Level.WARNING, "Could not keep the permissions of " + from + ": " + e.getMessage(), e);
        }
    }

    // Persist the rename itself. Not possible everywhere (Windows can't open a directory), the file is complete either way.
    private static void forceDirectory(@NotNull Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /**
     * Move a file that can't be read out of the way, as {@code <name>.corrupt-<timestamp>} next to it. It's kept for a
     * manual recovery instead of being overwritten by the next save.
     *
     * @return Where the file was kept, null if it couldn't be moved nor copied.
     */
    @Nullable
    public static Path keepAside(@NotNull Path file) {
        Path aside = asidePath(file);

        try {
            return Files.move(file, aside);
        } catch (IOException moveException) {
            try {
                // At least keep a copy, the original is overwritten by the next save.
                return Files.copy(file, aside, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException copyException) {
                copyException.addSuppressed(moveException);
                log.log(Level.SEVERE, "Could not keep " + file + " aside: " + copyException.getMessage(), copyException);
                return null;
            }
        }
    }

    @NotNull
    private static Path asidePath(@NotNull Path file) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        return file.resolveSibling(file.getFileName().toString() + ".corrupt-" + timestamp);
    }

    /**
     * Data written to a temporary file, waiting to replace its target.
     */
    public static final class PendingWrite implements Closeable {

        private final Path temp;

        private final Path target;

        private boolean committed = false;

        private PendingWrite(@NotNull Path temp, @NotNull Path target) {
            this.temp = temp;
            this.target = target;
        }

        /**
         * Replace the target with the written data in one step.
         */
        public void commit() throws IOException {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            committed = true;

            forceDirectory(target.getParent());
        }

        /**
         * Discard the written data, unless it was committed.
         */
        @Override
        public void close() throws IOException {
            if (!committed) {
                Files.deleteIfExists(temp);
            }
        }
    }
}
