package com.project.tradingBot.service;

import com.project.tradingBot.models.ActiveTrade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.project.tradingBot.util.ConsoleColors.*;

/**
 * Manages active trades: trailing stop-loss, T1/T2 target checks, and trade closure.
 */
@Service
public class TradeManagementService {

    @Autowired
    private SmartApiService smartApiService;

    @Autowired
    private TradePersistenceService tradePersistenceService;

    private final Map<String, ActiveTrade> activeTrades = new ConcurrentHashMap<>();

    // End-of-day summary
    private final Map<String, String> tradeResults = new ConcurrentHashMap<>();
    private final Map<String, Double> tradePnlPercent = new ConcurrentHashMap<>();

    public Map<String, ActiveTrade> getActiveTrades() { return activeTrades; }
    public List<String> getActiveTradeStocks() { return new ArrayList<>(activeTrades.keySet()); }
    public Map<String, String> getTradeResults() { return tradeResults; }
    public Map<String, Double> getTradePnlPercent() { return tradePnlPercent; }

    /**
     * Registers a new active trade for management.
     */
    public void registerTrade(ActiveTrade trade) {
        activeTrades.put(trade.getStock(), trade);
        System.out.println(GREEN + "[ENTRY] Trade registered for management: " + trade.getStock() + RESET);
    }

    /**
     * Manages trailing SL for all active trades.
     */
    public void manageTrailingSL(double target1Percent, double trailStepPercent) {
        if (activeTrades.isEmpty()) return;

        System.out.println(CYAN + "[TRAIL] Managing " + activeTrades.size() + " active trade(s)..." + RESET);

        for (String stock : new ArrayList<>(activeTrades.keySet())) {
            try {
                double ltp = smartApiService.getLTP(stock);
                if (ltp <= 0) {
                    System.out.println(YELLOW + "[TRAIL] Could not fetch LTP for " + stock + RESET);
                    continue;
                }

                ActiveTrade trade = activeTrades.get(stock);
                if (trade == null) continue;

                double entry = trade.getEntryPrice();
                double currentSL = trade.getTrailSL();
                String direction = trade.getDirection();
                double pnlPercent = trade.calculatePnlPercent(ltp);

                System.out.println(String.format("%s[TRAIL] %s | Dir: %s | Entry: %.2f | LTP: %.2f | P&L: %.2f%% | SL: %.2f | T1: %.2f %s | T2: %.2f%s",
                        CYAN, stock, direction, entry, ltp, pnlPercent, currentSL,
                        trade.getTarget1(), trade.isT1Hit() ? "✓" : "…", trade.getTarget2(), RESET));

                // Stage 2: Hard exit at T2 (1:3)
                if (("BUY".equals(direction) && ltp >= trade.getTarget2()) ||
                        ("SELL".equals(direction) && ltp <= trade.getTarget2())) {
                    System.out.println(String.format("%s[TRAIL] ★ T2 HARD EXIT for %s! P&L: %.2f%% (1:3 achieved)%s", GREEN, stock, pnlPercent, RESET));
                    closeTrade(stock, "TARGET2_HARD_EXIT");
                    continue;
                }

                // SL hit check
                if (("BUY".equals(direction) && ltp <= currentSL) ||
                        ("SELL".equals(direction) && ltp >= currentSL)) {
                    String reason = trade.isT1Hit() ? "TRAIL_SL" : "STOPLOSS";
                    System.out.println(String.format("%s[TRAIL] %s HIT for %s! P&L: %.2f%%%s",
                            trade.isT1Hit() ? YELLOW : RED, reason, stock, pnlPercent, RESET));
                    closeTrade(stock, reason);
                    continue;
                }

                // Stage 1: T1 hit (1:1.5) — lock profit and start trailing
                if (!trade.isT1Hit() && pnlPercent >= target1Percent) {
                    trade.setT1Hit(true);
                    double lockedSL;
                    if ("BUY".equals(direction)) {
                        lockedSL = entry * (1 + target1Percent / 100);
                    } else {
                        lockedSL = entry * (1 - target1Percent / 100);
                    }
                    trade.setTrailSL(lockedSL);
                    System.out.println(String.format("%s[TRAIL] ★ T1 HIT for %s! SL locked at %.2f (min %.2f%% profit guaranteed). Now riding to T2.%s",
                            GREEN, stock, lockedSL, target1Percent, RESET));
                    continue;
                }

                // Trail SL after T1 — never below T1 lock
                if (trade.isT1Hit()) {
                    double minSL;
                    if ("BUY".equals(direction)) {
                        minSL = entry * (1 + target1Percent / 100);
                    } else {
                        minSL = entry * (1 - target1Percent / 100);
                    }

                    double newSL;
                    if ("BUY".equals(direction)) {
                        newSL = ltp * (1 - trailStepPercent / 100);
                        newSL = Math.max(newSL, minSL);
                        if (newSL > currentSL) {
                            trade.setTrailSL(newSL);
                            System.out.println(String.format("%s[TRAIL] %s SL trailed UP: %.2f → %.2f (locking %.2f%% profit)%s",
                                    GREEN, stock, currentSL, newSL, ((newSL - entry) / entry) * 100, RESET));
                        }
                    } else {
                        newSL = ltp * (1 + trailStepPercent / 100);
                        newSL = Math.min(newSL, minSL);
                        if (newSL < currentSL) {
                            trade.setTrailSL(newSL);
                            System.out.println(String.format("%s[TRAIL] %s SL trailed DOWN: %.2f → %.2f (locking %.2f%% profit)%s",
                                    GREEN, stock, currentSL, newSL, ((entry - newSL) / entry) * 100, RESET));
                        }
                    }
                }

            } catch (Exception e) {
                System.out.println(RED + "[TRAIL] Error managing " + stock + ": " + e.getMessage() + RESET);
            }
        }
    }

    /**
     * Closes a trade, records results, and persists to database.
     */
    public void closeTrade(String stock, String reason) {
        System.out.println(YELLOW + "[CLOSE] Closing trade for " + stock + " | Reason: " + reason + RESET);

        ActiveTrade trade = activeTrades.get(stock);
        if (trade == null) {
            System.out.println(YELLOW + "[CLOSE] No active trade found for " + stock + RESET);
            return;
        }

        double ltp = smartApiService.getLTP(stock);
        double pnl = trade.calculatePnlPercent(ltp);
        tradeResults.put(stock, reason);
        tradePnlPercent.put(stock, pnl);

        tradePersistenceService.updateTradeExit(trade, ltp, reason, pnl);

        activeTrades.remove(stock);
    }
}
