package com.project.tradingBot.service;

import com.project.tradingBot.models.ActiveTrade;
import com.project.tradingBot.models.Candle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static com.project.tradingBot.util.ConsoleColors.*;

/**
 * Handles position sizing, order placement, and paper trade simulation.
 */
@Service
public class TradeExecutionService {

    @Autowired
    private SmartApiService smartApiService;

    @Value("${trading.paperTrade:true}")
    private boolean paperTrade;

    public boolean isPaperTrade() { return paperTrade; }

    /**
     * Calculates position size and places the order (or simulates it).
     * Returns an ActiveTrade if successful, null if order failed or skipped.
     */
    public ActiveTrade executeOrder(String stock, Candle c, String direction,
                                     double slPercent, double target1Percent, double target2Percent,
                                     double capitalFraction, double leverage, double simulatedBalance, double minCapital) {
        try {
            String mode = paperTrade ? "PAPER" : "LIVE";
            System.out.println(GREEN + "[ENTRY] [" + mode + "] Entry condition met for " + stock + " (" + direction + ")" + RESET);

            double balance = smartApiService.getBalance();
            if (balance <= 0) {
                if (paperTrade) {
                    balance = simulatedBalance;
                    System.out.println(YELLOW + "[ENTRY] [PAPER] Using simulated balance: ₹" + balance + RESET);
                } else {
                    System.out.println(YELLOW + "[ENTRY] Balance unavailable or zero — skipping trade." + RESET);
                    return null;
                }
            }

            double capital = balance * capitalFraction;
            double effectiveCapital = capital * leverage;
            double entryPrice = "BUY".equals(direction) ? c.getHigh() : c.getLow();

            int qty = (int) (effectiveCapital / entryPrice);
            if (qty <= 0 || effectiveCapital < minCapital) {
                System.out.println(String.format("%s[ENTRY] Skipping %s | Invalid Qty=%d | Cap=%.2f%s", RED, stock, qty, effectiveCapital, RESET));
                return null;
            }

            double sl, tgt1, tgt2;
            double slOffset, tgtOffset;
            if ("BUY".equals(direction)) {
                sl = entryPrice * (1 - slPercent / 100);
                tgt1 = entryPrice * (1 + target1Percent / 100);
                tgt2 = entryPrice * (1 + target2Percent / 100);
                slOffset = entryPrice - sl;
                tgtOffset = tgt2 - entryPrice;
            } else {
                sl = entryPrice * (1 + slPercent / 100);
                tgt1 = entryPrice * (1 - target1Percent / 100);
                tgt2 = entryPrice * (1 - target2Percent / 100);
                slOffset = sl - entryPrice;
                tgtOffset = entryPrice - tgt2;
            }

            System.out.println(String.format("%s[ENTRY] [%s] Placing %s order: %s | Qty=%d | Price=%.2f | SL=%.2f (offset=%.2f) | T1=%.2f (1:1.5) | T2=%.2f (1:3 hard exit) | Leverage=%.1fx%s",
                    GREEN, mode, direction, stock, qty, entryPrice, sl, slOffset, tgt1, tgt2, leverage, RESET));

            boolean placed;
            if (paperTrade) {
                placed = true;
                System.out.println(YELLOW + "[ENTRY] [PAPER] Simulated order placed — no real money involved." + RESET);
            } else {
                placed = smartApiService.placeBracketOrder(stock, direction, qty, entryPrice, slOffset, tgtOffset);
            }

            if (placed) {
                ActiveTrade trade = new ActiveTrade(stock, direction, entryPrice, qty, effectiveCapital, sl, tgt1, tgt2);

                System.out.println(String.format("%s[TRAIL] T1=%.2f (locks min %.2f%% profit) | T2=%.2f (hard exit at 1:3)%s",
                        CYAN, tgt1, target1Percent, tgt2, RESET));

                return trade;
            } else {
                System.out.println(RED + "[ENTRY] Order failed for " + stock + RESET);
                return null;
            }

        } catch (Exception e) {
            System.out.println(RED + "[ENTRY] Trade execution error for " + stock + ": " + e.getMessage() + RESET);
            return null;
        }
    }
}
