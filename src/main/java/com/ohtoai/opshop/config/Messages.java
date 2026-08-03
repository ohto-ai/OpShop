package com.ohtoai.opshop.config;

import com.ohtoai.opshop.OpShopPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Multi-locale message provider.
 * <p>
 * Load order: {@code lang/<locale>.yml} → embedded {@code lang/en.yml} (fallback)
 * → {@code config.yml messages:} section (legacy).
 * <p>
 * Usage:
 * <pre>
 *   messages.get("key")                        // lookup, colorize
 *   messages.get("key", "{a}", v1, "{b}", v2) // with replacements
 *   messages.get("key", "default &ctext")      // fallback if key missing
 * </pre>
 */
public class Messages {

    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("&([0-9a-fk-or])",
        Pattern.CASE_INSENSITIVE);
    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
        LegacyComponentSerializer.legacySection();

    private final OpShopPlugin plugin;
    private final Map<String, String> store = new HashMap<>();
    private String locale;

    public Messages(OpShopPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Reload from locale file + config fallback. */
    public void reload() {
        store.clear();
        this.locale = plugin.getPluginConfig().getLocale();
        plugin.getLogger().info("Loading messages for locale: " + locale);

        // 1. Load embedded en.yml (always available as ultimate fallback)
        loadEmbedded("lang/en.yml");

        // 2. Load locale file (overrides en)
        if (!"en".equals(locale)) {
            loadEmbedded("lang/" + locale + ".yml");
        }

        // 3. Load from plugin data folder (user overrides)
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (langFolder.exists()) {
            File userFile = new File(langFolder, locale + ".yml");
            if (userFile.exists()) {
                plugin.getLogger().info("Loading user lang override: " + userFile.getAbsolutePath());
                YamlConfiguration user = YamlConfiguration.loadConfiguration(userFile);
                for (String key : user.getKeys(true)) {
                    Object val = user.get(key);
                    if (val instanceof String s) store.put(key, s);
                }
            }
        }

        // 4. Legacy fallback: config.yml messages section
        Map<String, String> configMsgs = plugin.getPluginConfig().getMessageMap();
        for (var entry : configMsgs.entrySet()) {
            store.putIfAbsent(entry.getKey(), entry.getValue());
        }

        plugin.getLogger().info("Loaded " + store.size() + " message keys for locale: " + locale);
    }

    private void loadEmbedded(String path) {
        // Use plugin.getResource() — the correct Bukkit API for JAR resources
        var in = plugin.getResource(path);
        if (in == null) {
            plugin.getLogger().warning("Embedded lang file NOT FOUND in JAR: " + path);
            return;
        }
        try (in) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
            int before = store.size();
            flatten(yml, "", path);
            int loaded = store.size() - before;
            plugin.getLogger().info("Loaded " + loaded + " keys from " + path);
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                "Failed to load embedded lang: " + path, e);
        }
    }

    /**
     * Recursively flatten a Bukkit ConfigurationSection into dotted keys.
     * Handles both {@link org.bukkit.configuration.ConfigurationSection} (Bukkit's native
     * nested type) and raw {@link Map} (from non-Bukkit YAML parsers).
     */
    private void flatten(org.bukkit.configuration.ConfigurationSection section, String prefix, String source) {
        for (String key : section.getKeys(false)) {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            Object val = section.get(key);
            if (val instanceof String s) {
                store.put(fullKey, s);
            } else if (val instanceof org.bukkit.configuration.ConfigurationSection child) {
                // Bukkit nested section — recurse directly (no conversion needed)
                flatten(child, fullKey, source);
            } else if (val instanceof Map<?, ?> map) {
                // Raw Map fallback (shouldn't normally occur with Bukkit YAML)
                @SuppressWarnings("unchecked")
                var strMap = (Map<String, Object>) map;
                YamlConfiguration child = new YamlConfiguration();
                for (var entry : strMap.entrySet()) {
                    child.set(entry.getKey(), entry.getValue());
                }
                flatten(child, fullKey, source);
            }
        }
    }

    // --- Public API ---

    /** Get raw (un-colorized) message, or key itself if missing. */
    public String getRaw(String key) {
        return store.getOrDefault(key, key);
    }

    /** Get colorized message with Ampersand codes (uses '&'). */
    public String get(String key) {
        return colorize(getRaw(key));
    }

    /** Get with replacement pairs (k1, v1, k2, v2, ...). Colorized. */
    public String format(String key, String... replacements) {
        String msg = get(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    /** Get with a default fallback if the key is missing (no replacements). */
    public String get(String key, String defaultFallback) {
        String raw = store.get(key);
        if (raw == null) raw = defaultFallback;
        return colorize(raw);
    }

    /** Get with default fallback + replacement pairs. */
    public String get(String key, String defaultFallback, String... replacements) {
        String raw = store.get(key);
        if (raw == null) raw = defaultFallback;
        String msg = colorize(raw);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    /** Get as Adventure Component (tries MiniMessage first, then legacy fallback). */
    public Component getComponent(String key) {
        String raw = getRaw(key);
        try {
            return MiniMessage.miniMessage().deserialize(raw);
        } catch (Exception e) {
            return LEGACY_SERIALIZER.deserialize(colorize(raw));
        }
    }

    public String getPrefix() {
        return get("prefix");
    }

    public String getLocale() {
        return locale;
    }

    // --- Static utilities ---

    /** Convert '&' color codes to legacy '§' codes. */
    public static String colorize(String text) {
        if (text == null) return "";
        return COLOR_CODE_PATTERN.matcher(text).replaceAll("§$1");
    }

    /** Convert a colorized string to an Adventure Component. */
    public static Component toComponent(String colorizedText) {
        return LEGACY_SERIALIZER.deserialize(colorizedText);
    }
}
