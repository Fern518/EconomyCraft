package com.reazip.economycraft.stocks;

public final class StockHolding {
    public long quantity;
    public double avgCost;

    public StockHolding() {}

    public StockHolding(long quantity, double avgCost) {
        this.quantity = quantity;
        this.avgCost = avgCost;
    }
}
