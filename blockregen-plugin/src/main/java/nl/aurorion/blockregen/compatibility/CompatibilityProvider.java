package nl.aurorion.blockregen.compatibility;

import lombok.Getter;
import lombok.extern.java.Log;
import nl.aurorion.blockregen.BlockRegenPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.stream.Collectors;

// Provider for compatibility with other plugins.
@Log
public abstract class CompatibilityProvider {
    protected final BlockRegenPlugin plugin;

    @Getter
    @NotNull
    private final ProviderFeatureFlag[] features;

    @Getter
    private final String[] prefixes;

    public CompatibilityProvider(BlockRegenPlugin plugin) {
        this.plugin = plugin;
        this.prefixes = new String[0];
        this.features = new ProviderFeatureFlag[0];
    }

    public CompatibilityProvider(BlockRegenPlugin plugin, String[] prefixes, ProviderFeatureFlag[] features) {
        this.plugin = plugin;
        this.prefixes = prefixes;
        this.features = features;
    }

    public void onLoad() {
        //
    }

    @NotNull
    public String getJoinedFlags() {
        return Arrays.stream(getFeatures()).map(ProviderFeatureFlag::getName).collect(Collectors.joining("&7, &a"));
    }
}
