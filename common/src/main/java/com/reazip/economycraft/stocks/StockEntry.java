package com.reazip.economycraft.stocks;

public final class StockEntry {
    public String id;
    public String name;
    public double price;
    public double volatility;
    public double liquidity;
    public double[] history;
    public int historyHead = 0;

    public StockEntry() {}

    public StockEntry(String id, String name, double price, double volatility, double liquidity, int historySize) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.volatility = volatility;
        this.liquidity = liquidity;
        this.history = new double[historySize];
        for (int i = 0; i < historySize; i++) this.history[i] = price;
        this.historyHead = 0;
    }

    public synchronized void appendHistory(double p) {
        if (history == null || history.length == 0) return;
        historyHead = (historyHead + 1) % history.length;
        history[historyHead] = p;
    }

    public synchronized double[] snapshotHistory() {
        if (history == null) return new double[0];
        double[] out = new double[history.length];
        for (int i = 0; i < history.length; i++) {
            int idx = (historyHead + 1 + i) % history.length;
            out[i] = history[idx];
        }
        return out;
    }
}
