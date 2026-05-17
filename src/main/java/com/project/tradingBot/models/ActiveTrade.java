package com.project.tradingBot.models;

/**
 * Encapsulates all state for a single active trade being managed.
 */
public class ActiveTrade {

    private final String stock;
    private final String direction;
    private final double entryPrice;
    private final int quantity;
    private final double capitalUsed;
    private final double target1;
    private final double target2;

    private double trailSL;
    private boolean t1Hit;
    private Trade tradeEntity;

    public ActiveTrade(String stock, String direction, double entryPrice, int quantity,
                       double capitalUsed, double sl, double target1, double target2) {
        this.stock = stock;
        this.direction = direction;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.capitalUsed = capitalUsed;
        this.trailSL = sl;
        this.target1 = target1;
        this.target2 = target2;
        this.t1Hit = false;
    }

    public String getStock() { return stock; }
    public String getDirection() { return direction; }
    public double getEntryPrice() { return entryPrice; }
    public int getQuantity() { return quantity; }
    public double getCapitalUsed() { return capitalUsed; }
    public double getTarget1() { return target1; }
    public double getTarget2() { return target2; }

    public double getTrailSL() { return trailSL; }
    public void setTrailSL(double trailSL) { this.trailSL = trailSL; }

    public boolean isT1Hit() { return t1Hit; }
    public void setT1Hit(boolean t1Hit) { this.t1Hit = t1Hit; }

    public Trade getTradeEntity() { return tradeEntity; }
    public void setTradeEntity(Trade tradeEntity) { this.tradeEntity = tradeEntity; }

    public double calculatePnlPercent(double ltp) {
        if ("BUY".equals(direction)) {
            return ((ltp - entryPrice) / entryPrice) * 100;
        } else {
            return ((entryPrice - ltp) / entryPrice) * 100;
        }
    }

    @Override
    public String toString() {
        return String.format("ActiveTrade{%s %s @ %.2f | SL:%.2f | T1:%.2f %s | T2:%.2f}",
                direction, stock, entryPrice, trailSL, target1, t1Hit ? "✓" : "…", target2);
    }
}
