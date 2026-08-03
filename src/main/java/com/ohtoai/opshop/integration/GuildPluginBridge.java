package com.ohtoai.opshop.integration;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reflection-based bridge to GuildPlugin's API.
 * <p>
 * OpShop has zero compile-time dependency on GuildPlugin. All calls go through
 * reflection and fail gracefully — if GuildPlugin is missing or its API changes,
 * every method returns a safe default and {@link #isReady()} reports false.
 * <p>
 * Sync semantics: GuildPlugin's {@code GuildService} sync wrappers block on
 * {@code CompletableFuture.get()} (a DB round-trip). This is how GuildPlugin's
 * own commands run on the main thread, so calling from {@code buy()} is safe.
 */
public final class GuildPluginBridge {

    private boolean ready;
    private Logger logger;

    // com.guild.services.GuildService instance
    private Object guildService;

    // GuildService method handles
    private Method getPlayerGuildMethod;
    private Method getGuildByIdMethod;
    private Method getAllGuildsMethod;

    // com.guild.models.Guild method handles
    private Method guildGetId;
    private Method guildGetName;
    private Method guildGetTag;
    private boolean guildMethodsResolved;

    public GuildPluginBridge() {
    }

    /**
     * Detect GuildPlugin and bind all reflection handles.
     * Must be called after GuildPlugin is enabled (i.e. in or after HookManager.detectAll()).
     */
    public void detect() {
        this.logger = Logger.getLogger("OpShop-GuildBridge");
        try {
            var plugin = Bukkit.getPluginManager().getPlugin("GuildPlugin");
            if (plugin == null) {
                logger.info("GuildPlugin not found — guild features disabled.");
                return;
            }

            // 1. Get GuildService via GuildPlugin.getGuildService()
            Method getGuildService = plugin.getClass().getMethod("getGuildService");
            this.guildService = getGuildService.invoke(plugin);
            if (guildService == null) {
                logger.warning("GuildPlugin found but getGuildService() returned null.");
                return;
            }

            Class<?> serviceClass = guildService.getClass();

            // 2. Bind GuildService methods
            this.getPlayerGuildMethod = serviceClass.getMethod("getPlayerGuild", UUID.class);
            this.getGuildByIdMethod = serviceClass.getMethod("getGuildById", int.class);
            this.getAllGuildsMethod = serviceClass.getMethod("getAllGuilds");

            // 3. Bind Guild model methods (lazy — only if the class is on classpath)
            tryResolveGuildMethods();

            this.ready = true;
            logger.info("GuildPlugin integration enabled (reflection bridge).");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to bind GuildPlugin API via reflection. "
                + "Guild features will be disabled.", e);
            this.ready = false;
        }
    }

    private void tryResolveGuildMethods() {
        try {
            Class<?> guildClass = Class.forName("com.guild.models.Guild");
            this.guildGetId = guildClass.getMethod("getId");
            this.guildGetName = guildClass.getMethod("getName");
            this.guildGetTag = guildClass.getMethod("getTag");
            this.guildMethodsResolved = true;
        } catch (Exception e) {
            logger.warning("Could not resolve Guild model methods — guild name/tag lookups will fail.");
            this.guildMethodsResolved = false;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isReady() {
        return ready;
    }

    /**
     * Get the guild the player belongs to.
     *
     * @return GuildInfo or null if the player is not in a guild
     */
    public GuildInfo getPlayerGuild(UUID playerUuid) {
        if (!ready) return null;
        try {
            Object guild = getPlayerGuildMethod.invoke(guildService, playerUuid);
            if (guild == null) return null;
            return toGuildInfo(guild);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to call getPlayerGuild via reflection", e);
            return null;
        }
    }

    /**
     * Get guild by its numeric ID.
     *
     * @return GuildInfo or null if not found
     */
    public GuildInfo getGuildById(int guildId) {
        if (!ready) return null;
        try {
            Object guild = getGuildByIdMethod.invoke(guildService, guildId);
            if (guild == null) return null;
            return toGuildInfo(guild);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to call getGuildById via reflection", e);
            return null;
        }
    }

    /**
     * Get all guild names (for the whitelist GUI).
     */
    @SuppressWarnings("unchecked")
    public List<GuildInfo> getAllGuilds() {
        if (!ready) return Collections.emptyList();
        try {
            List<?> guilds = (List<?>) getAllGuildsMethod.invoke(guildService);
            if (guilds == null || guilds.isEmpty()) return Collections.emptyList();
            List<GuildInfo> result = new ArrayList<>(guilds.size());
            for (Object g : guilds) {
                GuildInfo info = toGuildInfo(g);
                if (info != null) result.add(info);
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to call getAllGuilds via reflection", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get a human-readable display name for a guild.
     */
    public String getGuildName(int guildId) {
        GuildInfo info = getGuildById(guildId);
        return info != null ? info.name() : null;
    }

    /**
     * Get the tag (short label) for a guild.
     */
    public String getGuildTag(int guildId) {
        GuildInfo info = getGuildById(guildId);
        return info != null ? info.tag() : null;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private GuildInfo toGuildInfo(Object guild) {
        if (!guildMethodsResolved || guild == null) return null;
        try {
            int id = (int) guildGetId.invoke(guild);
            String name = (String) guildGetName.invoke(guild);
            String tag = (String) guildGetTag.invoke(guild);
            return new GuildInfo(id, name, tag);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to extract guild fields via reflection", e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Value type
    // -------------------------------------------------------------------------

    /**
     * Decoupled snapshot of a guild's identity.
     */
    public record GuildInfo(int id, String name, String tag) {
    }
}
