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
    }

    private void loadEmbedded(String path) {
        try (var in = plugin.getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) return;
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
            flatten(yml, "", path);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load embedded lang: " + path);
        }
    }

    @SuppressWarnings("unchecked")
    private void flatten(YamlConfiguration yml, String prefix, String source) {
        for (String key : yml.getKeys(false)) {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            Object val = yml.get(key);
            if (val instanceof String s) {
                store.put(fullKey, s);
            } else if (val instanceof Map) {
                // Nested section — recurse (inefficient but simple)
                YamlConfiguration section = new YamlConfiguration();
                for (var entry : ((Map<String, Object>) val).entrySet()) {
                    section.set(entry.getKey(), entry.getValue());
                }
                flatten(section, fullKey, source);
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
