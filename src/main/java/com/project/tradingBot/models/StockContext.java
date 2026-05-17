package com.project.tradingBot.models;

/**
 * Holds per-stock contextual data loaded during strategy initialization.
 */
public class StockContext {

    private final String stock;
    private final double high15Min;
    private final double low15Min;
    private double avgVolume;
    private double atrPercent;

    public StockContext(String stock, double high15Min, double low15Min) {
        this.stock = stock;
        this.high15Min = high15Min;
        this.low15Min = low15Min;
    }

    public String getStock() { return stock; }
    public double getHigh15Min() { return high15Min; }
    public double getLow15Min() { return low15Min; }

    public double getAvgVolume() { return avgVolume; }
    public void setAvgVolume(double avgVolume) { this.avgVolume = avgVolume; }

    public double getAtrPercent() { return atrPercent; }
    public void setAtrPercent(double atrPercent) { this.atrPercent = atrPercent; }

    @Override
    public String toString() {
        return String.format("StockContext{%s | H:%.2f L:%.2f | ATR:%.2f%% | AvgVol:%.0f}",
                stock, high15Min, low15Min, atrPercent, avgVolume);
    }
}
