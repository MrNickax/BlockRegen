package nl.aurorion.blockregen;

import com.google.gson.GsonBuilder;
import nl.aurorion.blockregen.util.GsonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class GsonHelperTests {

    @TempDir
    Path dir;

    private final GsonHelper gsonHelper = new GsonHelper(new GsonBuilder());

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private List<String> filesIn(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    public void saveReplacesTheFileInsteadOfRewritingItInPlace() throws Exception {
        Path data = dir.resolve("Data.json");
        Files.write(data, "[\"old\"]".getBytes(StandardCharsets.UTF_8));

        // A second name for the current file. Truncating and rewriting it in place shows through this name, a crash
        // in the middle of that leaves a torn file behind.
        Path previous = dir.resolve("previous.json");
        Files.createLink(previous, data);

        gsonHelper.save(new ArrayList<>(Arrays.asList("new")), data).get(10, TimeUnit.SECONDS);

        assertEquals("[\"new\"]", read(data));
        assertEquals("[\"old\"]", read(previous));
    }

    @Test
    public void saveLeavesNoTemporaryFilesBehind() throws Exception {
        Path data = dir.resolve("Data.json");

        gsonHelper.save(new ArrayList<>(Arrays.asList("a", "b")), data).get(10, TimeUnit.SECONDS);
        gsonHelper.save(new ArrayList<>(Arrays.asList("c")), data).get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList("Data.json"), filesIn(dir));
        assertEquals("[\"c\"]", read(data));
    }

    @Test
    public void savedListLoadsBack() throws Exception {
        Path data = dir.resolve("Data.json");

        gsonHelper.save(new ArrayList<>(Arrays.asList("a", "b")), data).get(10, TimeUnit.SECONDS);

        List<String> loaded = gsonHelper.loadListAsync(data.toString(), String.class).get(10, TimeUnit.SECONDS);
        assertEquals(Arrays.asList("a", "b"), loaded);
    }

    @Test
    public void tornFileIsKeptAsideInsteadOfBeingLost() throws Exception {
        Path data = dir.resolve("Data.json");
        // What a crash in the middle of a rewrite leaves behind.
        byte[] torn = "[\"a\", \"b".getBytes(StandardCharsets.UTF_8);
        Files.write(data, torn);

        List<String> loaded = gsonHelper.loadListAsync(data.toString(), String.class).get(10, TimeUnit.SECONDS);

        assertNull(loaded);
        // Moved out of the way, the next save can't overwrite it.
        assertFalse(Files.exists(data));

        List<String> files = filesIn(dir);
        assertEquals(1, files.size(), "Expected only the kept file, got " + files);
        assertTrue(files.get(0).startsWith("Data.json.corrupt-"), files.get(0));
        assertArrayEquals(torn, Files.readAllBytes(dir.resolve(files.get(0))));
    }

    @Test
    public void missingFileLoadsAsNothing() throws Exception {
        Path data = dir.resolve("Data.json");

        List<String> loaded = gsonHelper.loadListAsync(data.toString(), String.class).get(5, TimeUnit.SECONDS);

        assertNull(loaded);
    }
}
