package com.ohtoai.opshop.database;

import java.util.UUID;

/**
 * Represents a single transaction in the shop.
 */
public class TransactionRecord {

    private long id;
    private final UUID playerUuid;
    private final String playerName;
    private final String shopItemId;
    private final TransactionType type;
    private final String currencyId;
    private final double pricePerUnit;
    private final int quantity;
    private final double totalPrice;
    private final byte[] itemSnapshot;
    private final long timestamp;

    public TransactionRecord(UUID playerUuid, String playerName, String shopItemId,
                             TransactionType type, String currencyId,
                             double pricePerUnit, int quantity, double totalPrice,
                             byte[] itemSnapshot, long timestamp) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.shopItemId = shopItemId;
        this.type = type;
        this.currencyId = currencyId;
        this.pricePerUnit = pricePerUnit;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
        this.itemSnapshot = itemSnapshot;
        this.timestamp = timestamp;
    }

    // Getters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public String getShopItemId() { return shopItemId; }
    public TransactionType getType() { return type; }
    public String getCurrencyId() { return currencyId; }
    public double getPricePerUnit() { return pricePerUnit; }
    public int getQuantity() { return quantity; }
    public double getTotalPrice() { return totalPrice; }
    public byte[] getItemSnapshot() { return itemSnapshot; }
    public long getTimestamp() { return timestamp; }

    public enum TransactionType {
        BUY, SELL
    }
}
