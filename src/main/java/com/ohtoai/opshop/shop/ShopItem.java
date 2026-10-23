package com.ohtoai.opshop.shop;

/**
 * Represents a single product in the shop.
 * Pricing: Vault, PlayerPoints, and multiple custom currencies.
 */
public class ShopItem {

    /**
     * Buy and sell price pair for a custom currency on this item.
     */
    public record CustomCurrencyPrice(double buyPrice, double sellPrice) {}

    private final String id;
    private int page;     // 0-indexed page number
    private int slot;     // 0-44 position within page
    private byte[] productItem;
    private boolean buyEnabled = true;
    private boolean sellEnabled = true;
    private int stock = -1;
    private int maxStock = -1;
    private long cooldownSeconds = 0;
    private String permissionRequired;
    private boolean applyPlaceholders = false;
    private int totalLimit = -1;
    private int totalSold = 0;
    private int playerLimit = -1;
    private boolean refundLimit = false;
    private boolean generateUniqueId = false;
    private final java.util.Set<Integer> allowedGuildIds = new java.util.HashSet<>();
    private boolean guildRestrictionEnabled = false;
    private boolean broadcastOnTrade = false;

    // Fixed currency pricing (Vault + PlayerPoints)
    private double vaultBuyPrice = 0;
    private double vaultSellPrice = 0;
    private double ppBuyPrice = 0;
    private double ppSellPrice = 0;

    // Multi custom currency pricing (currencyId → price pair)
    private final java.util.LinkedHashMap<String, CustomCurrencyPrice> customCurrencyPrices = new java.util.LinkedHashMap<>();

    // Legacy single custom currency fields (kept for DB migration, bridged to map)
    private String customCurrencyId; // null = none
    private double customBuyPrice = 0;
    private double customSellPrice = 0;

    private long createdAt;
    private long updatedAt;

    /**
     * Timestamp when this item was (re-)announced as new.
     * Newly created items are announced immediately; players see items whose
     * announcedAt is newer than their own "seen" watermark on join.
     * 0 = never announced (legacy items created before this feature).
     */
    private long announcedAt;

    public ShopItem(String id) {
        this.id = id;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
        this.announcedAt = now; // new items are announced on creation
    }

    // --- Getters ---
    public String getId() { return id; }
    public int getPage() { return page; }
    public int getSlot() { return slot; }
    /**
     * Returns the serialized item data shown in the shop GUI (item preview).
     * Currently identical to {@link #getProductItem()} — there is no separate
     * display item; the stored product is used for both display and delivery.
     */
    public byte[] getDisplayItem() { return productItem; }
    /** Returns the serialized item data delivered to the player on purchase. */
    public byte[] getProductItem() { return productItem; }
    public boolean isBuyEnabled() { return buyEnabled; }
    public boolean isSellEnabled() { return sellEnabled; }
    public int getStock() { return stock; }
    public int getMaxStock() { return maxStock; }
    public long getCooldownSeconds() { return cooldownSeconds; }
    public String getPermissionRequired() { return permissionRequired; }
    public boolean isApplyPlaceholders() { return applyPlaceholders; }
    public int getTotalLimit() { return totalLimit; }
    public int getTotalSold() { return totalSold; }
    public int getPlayerLimit() { return playerLimit; }
    public boolean isRefundLimit() { return refundLimit; }
    public boolean isGenerateUniqueId() { return generateUniqueId; }
    public java.util.Set<Integer> getAllowedGuildIds() { return allowedGuildIds; }
    public boolean isGuildRestricted() { return guildRestrictionEnabled && !allowedGuildIds.isEmpty(); }
    public boolean isGuildRestrictionEnabled() { return guildRestrictionEnabled; }
    public boolean isBroadcastOnTrade() { return broadcastOnTrade; }

    public double getVaultBuyPrice() { return vaultBuyPrice; }
    public double getVaultSellPrice() { return vaultSellPrice; }
    public double getPpBuyPrice() { return ppBuyPrice; }
    public double getPpSellPrice() { return ppSellPrice; }
    public String getCustomCurrencyId() { return customCurrencyId; }
    public double getCustomBuyPrice() { return customBuyPrice; }
    public double getCustomSellPrice() { return customSellPrice; }

    // --- Multi custom currency accessors ---

    /** Get all custom currency prices (immutable copy). */
    public java.util.Map<String, CustomCurrencyPrice> getCustomCurrencyPrices() {
        return java.util.Collections.unmodifiableMap(customCurrencyPrices);
    }

    /** Set buy & sell price for a custom currency. */
    public void setCustomCurrencyPrice(String currencyId, double buyPrice, double sellPrice) {
        customCurrencyPrices.put(currencyId, new CustomCurrencyPrice(buyPrice, sellPrice));
        touch();
    }

    /** Remove a custom currency from this item. */
    public void removeCustomCurrencyPrice(String currencyId) {
        customCurrencyPrices.remove(currencyId);
        touch();
    }

    /** Check whether this item uses a specific custom currency (has buy or sell price > 0). */
    public boolean hasCustomCurrency(String currencyId) {
        return customCurrencyPrices.containsKey(currencyId);
    }

    /** Replace all custom currency prices at once (used during DB load). */
    public void setCustomCurrencyPrices(java.util.Map<String, CustomCurrencyPrice> prices) {
        customCurrencyPrices.clear();
        if (prices != null) customCurrencyPrices.putAll(prices);
    }

    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public long getAnnouncedAt() { return announcedAt; }

    // --- Setters ---
    public void setPage(int page) { this.page = page; touch(); }
    public void setSlot(int slot) { this.slot = slot; touch(); }
    public void setDisplayItem(byte[] data) { this.productItem = data; touch(); }
    public void setProductItem(byte[] data) { this.productItem = data; touch(); }
    public void setBuyEnabled(boolean v) { this.buyEnabled = v; touch(); }
    public void setSellEnabled(boolean v) { this.sellEnabled = v; touch(); }
    public void setStock(int v) { this.stock = v; touch(); }
    public void setMaxStock(int v) { this.maxStock = v; touch(); }
    public void setCooldownSeconds(long v) { this.cooldownSeconds = v; touch(); }
    public void setPermissionRequired(String v) { this.permissionRequired = v; touch(); }
    public void setApplyPlaceholders(boolean v) { this.applyPlaceholders = v; touch(); }
    public void setTotalLimit(int v) { this.totalLimit = v; touch(); }
    public void setTotalSold(int v) { this.totalSold = v; touch(); }
    public void setPlayerLimit(int v) { this.playerLimit = v; touch(); }
    public void setRefundLimit(boolean v) { this.refundLimit = v; touch(); }
    public void setGenerateUniqueId(boolean v) { this.generateUniqueId = v; touch(); }
    public void setAllowedGuildIds(java.util.Set<Integer> ids) {
        allowedGuildIds.clear();
        if (ids != null) allowedGuildIds.addAll(ids);
    }
    public void setGuildRestrictionEnabled(boolean v) { this.guildRestrictionEnabled = v; touch(); }
    public void setBroadcastOnTrade(boolean v) { this.broadcastOnTrade = v; touch(); }

    public void setVaultBuyPrice(double v) { this.vaultBuyPrice = v; touch(); }
    public void setVaultSellPrice(double v) { this.vaultSellPrice = v; touch(); }
    public void setPpBuyPrice(double v) { this.ppBuyPrice = v; touch(); }
    public void setPpSellPrice(double v) { this.ppSellPrice = v; touch(); }
    public void setCustomCurrencyId(String v) { this.customCurrencyId = v; touch(); }
    public void setCustomBuyPrice(double v) { this.customBuyPrice = v; touch(); }
    public void setCustomSellPrice(double v) { this.customSellPrice = v; touch(); }
    public void setCreatedAt(long v) { this.createdAt = v; }
    public void setUpdatedAt(long v) { this.updatedAt = v; }
    /** Announcement marker — set from DB on load, or by the re-announce button. Not a content edit, so no touch(). */
    public void setAnnouncedAt(long v) { this.announcedAt = v; }

    private void touch() { this.updatedAt = System.currentTimeMillis(); }
}
