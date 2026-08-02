package com.ohtoai.opshop.shop;

/**
 * Represents a single product in the shop.
 * Pricing: three fixed currency slots — Vault, PlayerPoints, one custom currency.
 */
public class ShopItem {

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

    // Three-currency pricing
    private double vaultBuyPrice = 0;
    private double vaultSellPrice = 0;
    private double ppBuyPrice = 0;
    private double ppSellPrice = 0;
    private String customCurrencyId; // null = none
    private double customBuyPrice = 0;
    private double customSellPrice = 0;

    private long createdAt;
    private long updatedAt;

    public ShopItem(String id) {
        this.id = id;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // --- Getters ---
    public String getId() { return id; }
    public int getPage() { return page; }
    public int getSlot() { return slot; }
    public byte[] getDisplayItem() { return productItem; }
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

    public double getVaultBuyPrice() { return vaultBuyPrice; }
    public double getVaultSellPrice() { return vaultSellPrice; }
    public double getPpBuyPrice() { return ppBuyPrice; }
    public double getPpSellPrice() { return ppSellPrice; }
    public String getCustomCurrencyId() { return customCurrencyId; }
    public double getCustomBuyPrice() { return customBuyPrice; }
    public double getCustomSellPrice() { return customSellPrice; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

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

    public void setVaultBuyPrice(double v) { this.vaultBuyPrice = v; touch(); }
    public void setVaultSellPrice(double v) { this.vaultSellPrice = v; touch(); }
    public void setPpBuyPrice(double v) { this.ppBuyPrice = v; touch(); }
    public void setPpSellPrice(double v) { this.ppSellPrice = v; touch(); }
    public void setCustomCurrencyId(String v) { this.customCurrencyId = v; touch(); }
    public void setCustomBuyPrice(double v) { this.customBuyPrice = v; touch(); }
    public void setCustomSellPrice(double v) { this.customSellPrice = v; touch(); }
    public void setCreatedAt(long v) { this.createdAt = v; }
    public void setUpdatedAt(long v) { this.updatedAt = v; }

    private void touch() { this.updatedAt = System.currentTimeMillis(); }
}
