package nl.aurorion.blockregen.configuration;

import com.google.common.base.Charsets;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;
import nl.aurorion.blockregen.BlockRegenPlugin;
import nl.aurorion.blockregen.util.AtomicFiles;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log
public class ConfigFile {

    private static final Pattern TARGET_MATERIAL_PATTERN = Pattern.compile("^\\s*target-material:\\s*(.+?)(\\s*#.*)?$", Pattern.CASE_INSENSITIVE);

    private final BlockRegenPlugin plugin;

    @Getter
    private final String path;

    @Getter
    private File file;

    @Getter
    private FileConfiguration fileConfiguration;

    // The file couldn't be read or parsed, saving is disabled until a successful load.
    @Getter
    private volatile boolean loadFailed = false;

    @Getter
    @Setter
    private boolean forceEscapeTargetMaterial = false;

    public ConfigFile(BlockRegenPlugin plugin, String path) {
        this.path = path.contains(".yml") ? path : path + ".yml";
        this.plugin = plugin;
    }

    @Nullable
    public static Boolean parseOptionalBoolean(@Nullable Object obj) {
        if (obj == null) { return null; }

        if (obj instanceof Boolean) {
            return (Boolean) obj;
        }

        return null;
    }

    public void load() {
        this.file = new File(plugin.getDataFolder(), this.path);

        if (!file.exists()) {
            try {
                plugin.saveResource(this.path, false);
            } catch (IllegalArgumentException e) {
                try {
                    if (!file.createNewFile())
                        log.severe("Could not create file " + this.path);
                } catch (IOException e1) {
                    log.severe("Could not create file " + this.path);
                    return;
                }
            }

            log.info("Created file " + this.path);
        }

        // Force escape target-material when a glob pattern at the front is used avoiding YAML aliases.
        // target-material: *_ORE -> target-material: "*_ORE"

        try {
            BufferedReader reader = Files.newBufferedReader(file.toPath(), Charsets.UTF_8);
            StringBuilder builder = new StringBuilder();

            String line;
            try {
                // The same as FileConfiguration#load with escapes added.
                while ((line = reader.readLine()) != null) {
                    if (!this.forceEscapeTargetMaterial) {
                        builder.append(line);
                        builder.append('\n');
                        continue;
                    }

                    Matcher matcher = TARGET_MATERIAL_PATTERN.matcher(line);

                    if (!matcher.matches()) {
                        builder.append(line);
                        builder.append('\n');
                        continue;
                    }

                    String value = matcher.group(1).trim();

                    if (value.startsWith("*") && !value.startsWith("\"") && !value.startsWith("'")) {
                        String escapedValue = "\"" + value + "\"";
                        String escapedLine = line.replaceFirst(Pattern.quote(value), escapedValue);
                        builder.append(escapedLine);
                    } else {
                        builder.append(line);
                    }
                    builder.append('\n');
                }
            } finally {
                reader.close();
            }

            YamlConfiguration config = new YamlConfiguration();
            try {
                config.loadFromString(builder.toString());
                this.fileConfiguration = config;
                this.loadFailed = false;
            } catch (InvalidConfigurationException e) {
                failLoad("Invalid YAML configuration in", e);
            }
        } catch (IOException e) {
            failLoad("Could not read file", e);
        } catch (Exception e) {
            failLoad("Error processing file", e);
        }

        log.info("Loaded file " + this.path);
    }

    // Go on with an empty configuration, but never save it over the file: that would replace whatever the file holds.
    // The file stays in place, so the next start reports the same problem instead of quietly using defaults.
    private void failLoad(@NotNull String reason, @NotNull Exception e) {
        this.loadFailed = true;
        this.fileConfiguration = new YamlConfiguration();

        Path copy = AtomicFiles.copyAside(file.toPath());

        log.log(Level.SEVERE, reason + " " + this.path + ": " + e.getMessage()
                + ". Using an empty configuration, the file won't be saved until it's fixed and reloaded"
                + (copy == null ? "." : ". A copy was kept as " + copy.getFileName() + "."), e);
    }

    public void save() {
        save(fileConfiguration);
    }

    /**
     * Write the given configuration to this file. Skipped while the file failed to load.
     */
    public void save(@NotNull FileConfiguration configuration) {
        if (loadFailed) {
            log.warning("Not saving " + this.path + ", it failed to load. Fix it and reload.");
            return;
        }

        try {
            // Not FileConfiguration#save, it rewrites the file in place. A crash during that (Regions.yml is saved by
            // every auto-save) would leave a torn file.
            AtomicFiles.write(file.toPath(), configuration.saveToString().getBytes(Charsets.UTF_8));
        } catch (IOException e) {
            log.log(Level.SEVERE, "Could not save " + this.path + ": " + e.getMessage(), e);
        }
    }
}