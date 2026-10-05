package nl.aurorion.blockregen.util;

import lombok.extern.java.Log;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

/**
 * Crash-safe file writes.
 * <p>
 * Data is written to a temporary file next to the target, forced to the disk and then moved over the target in one
 * step. A crash (power loss, kill -9) leaves either the old or the new file, never a truncated one.
 */
@Log
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Atomically replace the contents of {@code target} with {@code data}.
     */
    public static void write(@NotNull Path target, @NotNull byte[] data) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);

        // Unique name, concurrent writers of the same file never share a temporary file.
        Path temp = Files.createTempFile(directory, target.getFileName().toString() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }

            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }

        forceDirectory(directory);
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
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        Path aside = file.resolveSibling(file.getFileName().toString() + ".corrupt-" + timestamp);

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
}
