package com.project.tradingBot.service;

import com.project.tradingBot.models.ActiveTrade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.project.tradingBot.util.ConsoleColors.*;

/**
 * Manages VWAP mean reversion trades with simple SL/Target logic (no two-stage T1/T2).
 * - SL: 0.3% beyond entry
 * - Target: 0.5% toward VWAP
 * - No trailing — just flat SL and target exit
 */
@Service
public class VwapTradeManagementService {

    @Autowired
    private SmartApiService smartApiService;

    @Autowired
    private TradePersistenceService tradePersistenceService;

    private final Map<String, ActiveTrade> activeTrades = new ConcurrentHashMap<>();
    private final Map<String, String> tradeResults = new ConcurrentHashMap<>();
    private final Map<String, Double> tradePnlPercent = new ConcurrentHashMap<>();

    public Map<String, ActiveTrade> getActiveTrades() { return activeTrades; }
    public List<String> getActiveTradeStocks() { return new ArrayList<>(activeTrades.keySet()); }
    public Map<String, String> getTradeResults() { return tradeResults; }
    public Map<String, Double> getTradePnlPercent() { return tradePnlPercent; }

    public void registerTrade(ActiveTrade trade) {
        activeTrades.put(trade.getStock(), trade);
        System.out.println(GREEN + "[VWAP-ENTRY] Trade registered for management: " + trade.getStock() + RESET);
    }

    /**
     * Checks all active VWAP trades against SL and target.
     * Simple logic: exit on SL hit or target hit, no trailing.
     */
    public void manageTrades(double targetPercent, double slPercent) {
        if (activeTrades.isEmpty()) return;

        System.out.println(CYAN + "[VWAP-MGMT] Managing " + activeTrades.size() + " active VWAP trade(s)..." + RESET);

        for (String stock : new ArrayList<>(activeTrades.keySet())) {
            try {
                double ltp = smartApiService.getLTP(stock);
                if (ltp <= 0) {
                    System.out.println(YELLOW + "[VWAP-MGMT] Could not fetch LTP for " + stock + RESET);
                    continue;
                }

                ActiveTrade trade = activeTrades.get(stock);
                if (trade == null) continue;

                double entry = trade.getEntryPrice();
                double pnlPercent = trade.calculatePnlPercent(ltp);
                String direction = trade.getDirection();

                System.out.println(String.format("%s[VWAP-MGMT] %s | Dir: %s | Entry: %.2f | LTP: %.2f | P&L: %.2f%% | SL: %.2f | Target: %.2f%s",
                        CYAN, stock, direction, entry, ltp, pnlPercent, trade.getTrailSL(), trade.getTarget1(), RESET));

                // Target hit check
                if (("BUY".equals(direction) && ltp >= trade.getTarget1()) ||
                        ("SELL".equals(direction) && ltp <= trade.getTarget1())) {
                    System.out.println(String.format("%s[VWAP-MGMT] ★ TARGET HIT for %s! P&L: %.2f%%%s",
                            GREEN, stock, pnlPercent, RESET));
                    closeTrade(stock, "VWAP_TARGET");
                    continue;
                }

                // SL hit check
                if (("BUY".equals(direction) && ltp <= trade.getTrailSL()) ||
                        ("SELL".equals(direction) && ltp >= trade.getTrailSL())) {
                    System.out.println(String.format("%s[VWAP-MGMT] ✗ STOPLOSS HIT for %s! P&L: %.2f%%%s",
                            RED, stock, pnlPercent, RESET));
                    closeTrade(stock, "VWAP_STOPLOSS");
                    continue;
                }

            } catch (Exception e) {
                System.out.println(RED + "[VWAP-MGMT] Error managing " + stock + ": " + e.getMessage() + RESET);
            }
        }
    }

    /**
     * Closes a trade, records results, and persists to database.
     */
    public void closeTrade(String stock, String reason) {
        System.out.println(YELLOW + "[VWAP-CLOSE] Closing trade for " + stock + " | Reason: " + reason + RESET);

        ActiveTrade trade = activeTrades.get(stock);
        if (trade == null) {
            System.out.println(YELLOW + "[VWAP-CLOSE] No active trade found for " + stock + RESET);
            return;
        }

        double ltp = smartApiService.getLTP(stock);
        double pnl = trade.calculatePnlPercent(ltp);
        tradeResults.put(stock, reason);
        tradePnlPercent.put(stock, pnl);

        tradePersistenceService.updateTradeExit(trade, ltp, reason, pnl);
        activeTrades.remove(stock);
    }

    /**
     * Closes all remaining VWAP trades at end of day.
     */
    public void closeAllTrades(String reason) {
        for (String stock : new ArrayList<>(activeTrades.keySet())) {
            closeTrade(stock, reason);
        }
    }
}
