package com.project.tradingBot.service;

import com.project.tradingBot.models.Candle;
import com.project.tradingBot.models.StockContext;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.project.tradingBot.util.ConsoleColors.*;

/**
 * Detects breakout signals and handles retest confirmation logic.
 */
@Service
public class SignalDetectionService {

    // Pending retest state
    private final Map<String, Double> pendingRetestLevel = new ConcurrentHashMap<>();
    private final Map<String, String> pendingRetestDirection = new ConcurrentHashMap<>();

    /**
     * Result of processing a stock candle.
     */
    public enum Signal {
        NONE, BREAKOUT_PENDING, RETEST_CONFIRMED, RETEST_FAILED, INVALIDATED, WAITING_RETEST
    }

    public static class SignalResult {
        private final Signal signal;
        private final String direction;

        public SignalResult(Signal signal, String direction) {
            this.signal = signal;
            this.direction = direction;
        }

        public Signal getSignal() { return signal; }
        public String getDirection() { return direction; }
    }

    public boolean hasPendingRetest(String stock) {
        return pendingRetestLevel.containsKey(stock);
    }

    /**
     * Evaluates a candle for breakout signals on a given stock.
     */
    public SignalResult evaluateBreakout(String stock, Candle c, StockContext ctx,
                                         boolean isPositiveDay, double volumeMultiplier, double breakoutRangeMax) {
        double rangePercent = ((c.getHigh() - c.getLow()) / c.getLow()) * 100;
        double avgVol = ctx.getAvgVolume();
        boolean volumeConfirmed = avgVol > 0 && c.getVolume() >= (avgVol * volumeMultiplier);

        if (isPositiveDay) {
            if (c.getClose() > ctx.getHigh15Min() && rangePercent <= breakoutRangeMax) {
                if (!volumeConfirmed) {
                    System.out.println(String.format("%s[VOLUME] %s breakout WITHOUT volume (%.0f < %.0f × %.1f). Skipping.%s",
                            YELLOW, stock, c.getVolume(), avgVol, volumeMultiplier, RESET));
                    return new SignalResult(Signal.NONE, null);
                }
                System.out.println(String.format("%s[BREAKOUT] %s closed %.2f above 15-min high %.2f with volume %.0f (%.1fx avg). Waiting for retest...%s",
                        GREEN, stock, c.getClose(), ctx.getHigh15Min(), c.getVolume(), c.getVolume() / avgVol, RESET));
                pendingRetestLevel.put(stock, ctx.getHigh15Min());
                pendingRetestDirection.put(stock, "BUY");
                return new SignalResult(Signal.BREAKOUT_PENDING, "BUY");
            } else if (c.getLow() < ctx.getLow15Min()) {
                System.out.println(YELLOW + "[EXIT] " + stock + " broke low → Removed from watchlist." + RESET);
                return new SignalResult(Signal.INVALIDATED, null);
            }
        } else {
            if (c.getClose() < ctx.getLow15Min() && rangePercent <= breakoutRangeMax) {
                if (!volumeConfirmed) {
                    System.out.println(String.format("%s[VOLUME] %s breakdown WITHOUT volume (%.0f < %.0f × %.1f). Skipping.%s",
                            YELLOW, stock, c.getVolume(), avgVol, volumeMultiplier, RESET));
                    return new SignalResult(Signal.NONE, null);
                }
                System.out.println(String.format("%s[BREAKDOWN] %s closed %.2f below 15-min low %.2f with volume %.0f (%.1fx avg). Waiting for retest...%s",
                        RED, stock, c.getClose(), ctx.getLow15Min(), c.getVolume(), c.getVolume() / avgVol, RESET));
                pendingRetestLevel.put(stock, ctx.getLow15Min());
                pendingRetestDirection.put(stock, "SELL");
                return new SignalResult(Signal.BREAKOUT_PENDING, "SELL");
            } else if (c.getHigh() > ctx.getHigh15Min()) {
                System.out.println(YELLOW + "[EXIT] " + stock + " reversed → Removed from watchlist." + RESET);
                return new SignalResult(Signal.INVALIDATED, null);
            }
        }

        return new SignalResult(Signal.NONE, null);
    }

    /**
     * Checks if a stock's retest has been confirmed or failed.
     */
    public SignalResult evaluateRetest(String stock, Candle c, double touchTolerance, double failTolerance) {
        double level = pendingRetestLevel.get(stock);
        String direction = pendingRetestDirection.get(stock);

        if ("BUY".equals(direction)) {
            if (c.getLow() <= level * (1 + touchTolerance) && c.getClose() > level) {
                System.out.println(String.format("%s[RETEST] %s retested %.2f and held (Close: %.2f) → Confirming BUY entry!%s",
                        GREEN, stock, level, c.getClose(), RESET));
                clearRetest(stock);
                return new SignalResult(Signal.RETEST_CONFIRMED, "BUY");
            } else if (c.getClose() < level * (1 - failTolerance)) {
                System.out.println(YELLOW + "[RETEST] " + stock + " failed retest (Close: " + c.getClose() + " < " + level + ") → Removed." + RESET);
                clearRetest(stock);
                return new SignalResult(Signal.RETEST_FAILED, null);
            } else {
                System.out.println(CYAN + "[RETEST] " + stock + " still waiting for retest at " + level + RESET);
                return new SignalResult(Signal.WAITING_RETEST, "BUY");
            }
        } else {
            if (c.getHigh() >= level * (1 - touchTolerance) && c.getClose() < level) {
                System.out.println(String.format("%s[RETEST] %s retested %.2f and rejected (Close: %.2f) → Confirming SELL entry!%s",
                        RED, stock, level, c.getClose(), RESET));
                clearRetest(stock);
                return new SignalResult(Signal.RETEST_CONFIRMED, "SELL");
            } else if (c.getClose() > level * (1 + failTolerance)) {
                System.out.println(YELLOW + "[RETEST] " + stock + " failed retest (Close: " + c.getClose() + " > " + level + ") → Removed." + RESET);
                clearRetest(stock);
                return new SignalResult(Signal.RETEST_FAILED, null);
            } else {
                System.out.println(CYAN + "[RETEST] " + stock + " still waiting for retest at " + level + RESET);
                return new SignalResult(Signal.WAITING_RETEST, "SELL");
            }
        }
    }

    private void clearRetest(String stock) {
        pendingRetestLevel.remove(stock);
        pendingRetestDirection.remove(stock);
    }
}
