package nl.aurorion.blockregen;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import nl.aurorion.blockregen.util.AtomicFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class AtomicFilesTests {

    // POSIX file system in memory, so the permission tests run on any OS.
    private final FileSystem posix = Jimfs.newFileSystem(Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build());

    @AfterEach
    public void close() throws IOException {
        posix.close();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String names(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).sorted().collect(Collectors.joining(", "));
        }
    }

    @Test
    public void replacedFileKeepsItsPermissionsAndGroup() throws Exception {
        Path directory = Files.createDirectories(posix.getPath("/server/plugins/BlockRegen"));
        Path target = directory.resolve("Data.json");
        Files.write(target, bytes("[]"));

        GroupPrincipal group = posix.getUserPrincipalLookupService().lookupPrincipalByGroupName("minecraft");
        PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        view.setGroup(group);
        view.setPermissions(PosixFilePermissions.fromString("rw-rw----"));

        AtomicFiles.write(target, bytes("[\"new\"]"));

        PosixFileAttributes attributes = Files.readAttributes(target, PosixFileAttributes.class);
        assertEquals("rw-rw----", PosixFilePermissions.toString(attributes.permissions()));
        assertEquals(group, attributes.group());
        assertEquals("[\"new\"]", read(target));
        assertEquals("Data.json", names(directory));
    }

    @Test
    public void writeGoesThroughASymbolicLink() throws Exception {
        Path storage = Files.createDirectories(posix.getPath("/storage"));
        Path real = storage.resolve("Data.json");
        Files.write(real, bytes("[]"));

        Path directory = Files.createDirectories(posix.getPath("/server/plugins/BlockRegen"));
        Path link = Files.createSymbolicLink(directory.resolve("Data.json"), real);

        AtomicFiles.write(link, bytes("[\"new\"]"));

        assertTrue(Files.isSymbolicLink(link), "The link must not be replaced by a regular file");
        assertEquals("[\"new\"]", read(real));
        assertEquals("Data.json", names(storage));
    }

    @Test
    public void writeGoesThroughADanglingSymbolicLink() throws Exception {
        Path storage = Files.createDirectories(posix.getPath("/storage"));
        Path real = storage.resolve("Data.json");

        Path directory = Files.createDirectories(posix.getPath("/server/plugins/BlockRegen"));
        Path link = Files.createSymbolicLink(directory.resolve("Data.json"), real);

        AtomicFiles.write(link, bytes("[]"));

        assertTrue(Files.isSymbolicLink(link), "The link must not be replaced by a regular file");
        assertEquals("[]", read(real));
    }

    @Test
    public void newFileGetsTheUsualPermissions(@TempDir Path directory) throws Exception {
        // The real umask only exists on a POSIX default file system (the CI runs on Linux).
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        Path reference = Files.createFile(directory.resolve("reference"));
        Path target = directory.resolve("Data.json");

        AtomicFiles.write(target, bytes("[]"));

        assertEquals(Files.getPosixFilePermissions(reference), Files.getPosixFilePermissions(target));
    }
}
