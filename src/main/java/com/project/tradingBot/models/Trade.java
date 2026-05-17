package com.project.tradingBot.models;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate tradeDate;
    private String stock;
    private String direction;       // BUY or SELL
    private String mode;            // PAPER or LIVE

    private double entryPrice;
    private double exitPrice;
    private int quantity;
    private double capitalUsed;

    private double stopLoss;
    private double target1;         // T1 (1:1.5)
    private double target2;         // T2 (1:3)

    private boolean t1Hit;
    private String exitReason;      // TARGET2_HARD_EXIT, TRAIL_SL, STOPLOSS, CUTOFF
    private double pnlPercent;
    private double pnlAmount;

    private double atrPercent;
    private double avgVolume;
    private double breakoutVolume;

    private String niftyBias;       // POSITIVE or NEGATIVE
    private double niftyChangePercent;

    private LocalDateTime entryTime;
    private LocalDateTime exitTime;

    public Trade() {}

    // --- Getters and Setters ---

    public Long getId() { return id; }

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public String getStock() { return stock; }
    public void setStock(String stock) { this.stock = stock; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public double getEntryPrice() { return entryPrice; }
    public void setEntryPrice(double entryPrice) { this.entryPrice = entryPrice; }

    public double getExitPrice() { return exitPrice; }
    public void setExitPrice(double exitPrice) { this.exitPrice = exitPrice; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public double getCapitalUsed() { return capitalUsed; }
    public void setCapitalUsed(double capitalUsed) { this.capitalUsed = capitalUsed; }

    public double getStopLoss() { return stopLoss; }
    public void setStopLoss(double stopLoss) { this.stopLoss = stopLoss; }

    public double getTarget1() { return target1; }
    public void setTarget1(double target1) { this.target1 = target1; }

    public double getTarget2() { return target2; }
    public void setTarget2(double target2) { this.target2 = target2; }

    public boolean isT1Hit() { return t1Hit; }
    public void setT1Hit(boolean t1Hit) { this.t1Hit = t1Hit; }

    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }

    public double getPnlPercent() { return pnlPercent; }
    public void setPnlPercent(double pnlPercent) { this.pnlPercent = pnlPercent; }

    public double getPnlAmount() { return pnlAmount; }
    public void setPnlAmount(double pnlAmount) { this.pnlAmount = pnlAmount; }

    public double getAtrPercent() { return atrPercent; }
    public void setAtrPercent(double atrPercent) { this.atrPercent = atrPercent; }

    public double getAvgVolume() { return avgVolume; }
    public void setAvgVolume(double avgVolume) { this.avgVolume = avgVolume; }

    public double getBreakoutVolume() { return breakoutVolume; }
    public void setBreakoutVolume(double breakoutVolume) { this.breakoutVolume = breakoutVolume; }

    public String getNiftyBias() { return niftyBias; }
    public void setNiftyBias(String niftyBias) { this.niftyBias = niftyBias; }

    public double getNiftyChangePercent() { return niftyChangePercent; }
    public void setNiftyChangePercent(double niftyChangePercent) { this.niftyChangePercent = niftyChangePercent; }

    public LocalDateTime getEntryTime() { return entryTime; }
    public void setEntryTime(LocalDateTime entryTime) { this.entryTime = entryTime; }

    public LocalDateTime getExitTime() { return exitTime; }
    public void setExitTime(LocalDateTime exitTime) { this.exitTime = exitTime; }

    @Override
    public String toString() {
        return String.format("Trade{%s %s %s @ %.2f → %.2f | P&L: %.2f%% | %s}",
                tradeDate, direction, stock, entryPrice, exitPrice, pnlPercent, exitReason);
    }
}
