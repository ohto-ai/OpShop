package com.ohtoai.opshop.shop;

/**
 * Represents buy and sell prices for a specific currency on a shop item.
 */
public class PriceConfig {

    private final String currencyId;
    private final double buyPrice;
    private final double sellPrice;

    public PriceConfig(String currencyId, double buyPrice, double sellPrice) {
        this.currencyId = currencyId;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
    }

    public String getCurrencyId() { return currencyId; }
    public double getBuyPrice() { return buyPrice; }
    public double getSellPrice() { return sellPrice; }

    /** Whether this currency can be used for buying */
    public boolean canBuy() { return buyPrice > 0; }

    /** Whether this currency can be used for selling */
    public boolean canSell() { return sellPrice > 0; }

    @Override
    public String toString() {
        return "PriceConfig{currency=" + currencyId
            + ", buy=" + buyPrice + ", sell=" + sellPrice + "}";
    }
}
