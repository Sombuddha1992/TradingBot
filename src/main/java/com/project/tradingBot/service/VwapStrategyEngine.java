package com.project.tradingBot.service;

import com.project.tradingBot.models.ActiveTrade;
import com.project.tradingBot.models.Candle;
import com.project.tradingBot.service.VwapSignalDetectionService.VwapSignal;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.project.tradingBot.util.ConsoleColors.*;

/**
 * VWAP Mean Reversion Strategy Engine.
 *
 * Runs 12:00 PM – 3:00 PM after ORB shuts down.
 * Universe: hardcoded Nifty 50 stocks.
 * Pre-market gate: India VIX < 17.
 * Entry: VWAP deviation 1.3–2.5% + volume spike + reversal candle.
 * Target: 0.5%, SL: 0.3%, R:R = 1:1.67.
 */
@Service
public class VwapStrategyEngine {

    @Autowired private SmartApiService smartApiService;
    @Autowired private VwapSignalDetectionService vwapSignalService;
    @Autowired private VwapTradeManagementService vwapTradeManagement;
    @Autowired private TradeExecutionService tradeExecutionService;
    @Autowired private TradePersistenceService tradePersistenceService;
    @Autowired private ConfigurableApplicationContext applicationContext;

    private ScheduledExecutorService executor;
    private volatile boolean initialized = false;
    private volatile boolean stopped = false;
    private final AtomicInteger tradesDone = new AtomicInteger(0);

    // ---- Strategy constants ----
    private static final double VIX_MAX_THRESHOLD = 17.0;
    private static final double VWAP_DEVIATION_MIN = 1.3;
    private static final double VWAP_DEVIATION_MAX = 2.5;
    private static final double SL_PERCENT = 0.30;
    private static final double TARGET_PERCENT = 0.50;
    private static final double VOLUME_MULTIPLIER = 1.5;
    private static final int MAX_TRADES = 6;
    private static final int POLL_INTERVAL_LIVE = 300;
    private static final int POLL_INTERVAL_PAPER = 60;
    private static final LocalTime TRADE_START_TIME = LocalTime.of(12, 0);
    private static final LocalTime TRADE_CUTOFF_TIME = LocalTime.of(15, 0);
    private static final double CAPITAL_FRACTION = 0.5;
    private static final double LEVERAGE = 5.0;
    private static final double SIMULATED_BALANCE = 100000;
    private static final double MIN_EFFECTIVE_CAPITAL = 1000;

    // Per-stock cumulative VWAP state
    private final Map<String, Double> cumulativePriceVolume = new ConcurrentHashMap<>();
    private final Map<String, Double> cumulativeVolume = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> candleVolumes = new ConcurrentHashMap<>();

    // Nifty 50 stock symbols
    private static final List<String> NIFTY_50_STOCKS = List.of(
            "ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK",
            "BAJAJ-AUTO", "BAJFINANCE", "BAJAJFINSV", "BEL", "BPCL",
            "BHARTIARTL", "BRITANNIA", "CIPLA", "COALINDIA", "DRREDDY",
            "EICHERMOT", "ETERNAL", "GRASIM", "HCLTECH", "HDFCBANK",
            "HDFCLIFE", "HEROMOTOCO", "HINDALCO", "HINDUNILVR", "ICICIBANK",
            "ITC", "INDUSINDBK", "INFY", "JSWSTEEL", "KOTAKBANK",
            "LT", "M&M", "MARUTI", "NTPC", "NESTLEIND",
            "ONGC", "POWERGRID", "RELIANCE", "SBILIFE", "SBIN",
            "SUNPHARMA", "TCS", "TATACONSUM", "TATAMOTORS", "TATASTEEL",
            "TECHM", "TITAN", "TRENT", "ULTRACEMCO", "WIPRO"
    );

    // Stocks that resolved to valid tokens
    private List<String> validStocks = new ArrayList<>();


    // ===================== INIT =====================
    public void init() {
        try {
            System.out.println(CYAN + "===================== [VWAP-INIT] VWAP STRATEGY INITIALIZATION =====================" + RESET);

            // Validate Nifty 50 symbols against scrip master
            validStocks = new ArrayList<>();
            for (String symbol : NIFTY_50_STOCKS) {
                String token = PopulateScanResultService.getMasterEquitiesMap().get(symbol + "-EQ");
                if (token != null) {
                    validStocks.add(symbol);
                } else {
                    System.out.println(YELLOW + "[VWAP-INIT] Symbol not found in scrip master: " + symbol + " → Skipping" + RESET);
                }
            }

            System.out.println(GREEN + "[VWAP-INIT] Resolved " + validStocks.size() + "/" + NIFTY_50_STOCKS.size() + " Nifty 50 symbols." + RESET);

            if (validStocks.isEmpty()) {
                System.out.println(RED + "[VWAP-INIT] No valid stocks found. VWAP strategy will not run." + RESET);
                return;
            }

            initialized = true;
            System.out.println(CYAN + "======================================================================" + RESET);

        } catch (Exception e) {
            System.out.println(RED + "[VWAP-INIT] Initialization failed: " + e.getMessage() + RESET);
            e.printStackTrace();
        }
    }


    // ===================== START =====================
    public void start() {
        if (!initialized) {
            System.out.println(RED + "[VWAP-START] VWAP strategy not initialized. Skipping." + RESET);
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("VwapStrategyEngine-Poller");
            t.setDaemon(false);
            return t;
        });

        long initialDelay = computeInitialDelaySeconds();
        System.out.println(CYAN + "[VWAP-START] VWAP strategy will start polling after " + initialDelay + " seconds (at ~12:00 PM)." + RESET);
        System.out.println(CYAN + "[VWAP-START] Trade cutoff: " + TRADE_CUTOFF_TIME + " | SL: " + SL_PERCENT + "% | Target: " + TARGET_PERCENT + "%" + RESET);

        boolean paperTrade = tradeExecutionService.isPaperTrade();
        System.out.println(paperTrade
                ? YELLOW + "[VWAP-START] *** PAPER TRADE MODE — 60s polling when trades active, 300s for scanning ***" + RESET
                : RED + "[VWAP-START] *** LIVE TRADE MODE ***" + RESET);

        // Use dynamic scheduling: 60s when paper trades active, 300s for signal scanning
        executor.schedule(() -> pollAndReschedule(), initialDelay, TimeUnit.SECONDS);
    }

    /**
     * Runs one poll cycle, then schedules the next with dynamic interval:
     * - 60s if paper trade mode AND active trades exist (precise SL/target monitoring)
     * - 300s otherwise (5-min candle-based signal scanning)
     */
    private void pollAndReschedule() {
        try {
            pollVwapStocks();
            vwapTradeManagement.manageTrades(TARGET_PERCENT, SL_PERCENT);
        } catch (Exception e) {
            System.out.println(RED + "[VWAP-SCHEDULE] Uncaught exception: " + e.getMessage() + RESET);
            e.printStackTrace();
        }

        if (stopped) return;

        boolean hasActiveTrades = !vwapTradeManagement.getActiveTrades().isEmpty();
        int nextInterval = (tradeExecutionService.isPaperTrade() && hasActiveTrades)
                ? POLL_INTERVAL_PAPER : POLL_INTERVAL_LIVE;
        System.out.println(CYAN + "[VWAP-SCHEDULE] Next poll in " + nextInterval + "s"
                + (hasActiveTrades ? " (monitoring active trades)" : " (scanning for signals)") + RESET);
        executor.schedule(() -> pollAndReschedule(), nextInterval, TimeUnit.SECONDS);
    }


    // ===================== POLL =====================
    private void pollVwapStocks() {
        System.out.println(CYAN + "[VWAP-POLL] ============================================================" + RESET);
        System.out.println(YELLOW + "[VWAP-POLL] Polling at " + LocalTime.now() + " | Trades done: " + tradesDone.get() + RESET);

        // VIX gate — check every poll cycle (VIX can change intraday)
        double vix = smartApiService.getIndiaVix();
        if (vix > 0) {
            System.out.println(String.format("%s[VWAP-POLL] India VIX: %.2f (threshold: %.1f)%s",
                    vix > VIX_MAX_THRESHOLD ? RED : GREEN, vix, VIX_MAX_THRESHOLD, RESET));
            if (vix > VIX_MAX_THRESHOLD) {
                System.out.println(RED + "[VWAP-POLL] VIX too high — skipping VWAP trading this cycle." + RESET);
                // Still manage existing trades
                return;
            }
        } else {
            System.out.println(YELLOW + "[VWAP-POLL] Could not fetch VIX — proceeding with caution." + RESET);
        }

        // Max trades check
        if (tradesDone.get() >= MAX_TRADES) {
            List<String> activeStocks = vwapTradeManagement.getActiveTradeStocks();
            if (activeStocks.isEmpty()) {
                System.out.println(YELLOW + "[VWAP-POLL] Max trades reached and no active trades. Stopping VWAP." + RESET);
                cleanupAndExit();
                return;
            }
            System.out.println(YELLOW + "[VWAP-POLL] Max trades reached — only managing existing positions." + RESET);
            return;
        }

        // Cutoff time check
        if (LocalTime.now().isAfter(TRADE_CUTOFF_TIME)) {
            List<String> activeStocks = vwapTradeManagement.getActiveTradeStocks();
            if (activeStocks.isEmpty()) {
                System.out.println(YELLOW + "[VWAP-POLL] Past cutoff with no active trades. Stopping VWAP." + RESET);
                cleanupAndExit();
                return;
            }
            System.out.println(YELLOW + "[VWAP-POLL] Past cutoff — only managing existing trades." + RESET);
            return;
        }

        // Process each valid stock
        for (String stock : validStocks) {
            if (tradesDone.get() >= MAX_TRADES) break;

            // Skip if we already have an active trade on this stock
            if (vwapTradeManagement.getActiveTrades().containsKey(stock)) continue;

            try {
                processVwapStock(stock);
            } catch (Exception e) {
                System.out.println(RED + "[VWAP-POLL] Error processing " + stock + ": " + e.getMessage() + RESET);
            }
        }

        System.out.println(CYAN + "[VWAP-POLL] Cycle completed." + RESET);
    }


    // ===================== PROCESS STOCK =====================
    private void processVwapStock(String stock) throws InterruptedException {
        // Fetch all 5-min candles from 9:15 to now (on first call) or latest candle (incremental)
        String today = LocalDate.now().toString();
        String fromTime = today + " 09:15";
        String toTime = today + " " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

        List<Candle> candles;
        try {
            candles = smartApiService.getHistoricalCandles(stock, "FIVE_MINUTE", fromTime, toTime);
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (msg.contains("session") || msg.contains("token") || msg.contains("unauthorized") || msg.contains("AB1004")) {
                System.out.println(YELLOW + "[VWAP-AUTH] Session expired → Reauthenticating..." + RESET);
                smartApiService.login();
                Thread.sleep(2000);
                candles = smartApiService.getHistoricalCandles(stock, "FIVE_MINUTE", fromTime, toTime);
            } else {
                throw new RuntimeException("Failed to fetch candles for " + stock + ": " + msg, ex);
            }
        }

        if (candles == null || candles.size() < 3) {
            return; // Not enough data for VWAP
        }

        // Calculate VWAP from all candles
        double sumPriceVol = 0;
        double sumVol = 0;
        List<Double> volumes = new ArrayList<>();

        for (Candle c : candles) {
            double typicalPrice = (c.getHigh() + c.getLow() + c.getClose()) / 3.0;
            sumPriceVol += typicalPrice * c.getVolume();
            sumVol += c.getVolume();
            volumes.add(c.getVolume());
        }

        if (sumVol <= 0) return;
        double vwap = sumPriceVol / sumVol;

        // Latest candle
        Candle latest = candles.get(candles.size() - 1);

        // Average volume of prior candles (exclude the latest)
        double avgCandleVolume = 0;
        if (volumes.size() > 1) {
            double volSum = 0;
            for (int i = 0; i < volumes.size() - 1; i++) {
                volSum += volumes.get(i);
            }
            avgCandleVolume = volSum / (volumes.size() - 1);
        }

        // Evaluate signal
        VwapSignal signal = vwapSignalService.evaluate(latest, vwap, VWAP_DEVIATION_MIN, VWAP_DEVIATION_MAX,
                avgCandleVolume, VOLUME_MULTIPLIER);

        if (signal == VwapSignal.NONE) return;

        // Execute trade
        synchronized (this) {
            if (tradesDone.get() >= MAX_TRADES) return;

            String direction = signal == VwapSignal.BUY ? "BUY" : "SELL";

            // For VWAP trades, target1 = our target (0.5%), target2 = same (no two-stage)
            ActiveTrade trade = tradeExecutionService.executeOrder(
                    stock, latest, direction,
                    SL_PERCENT, TARGET_PERCENT, TARGET_PERCENT,
                    CAPITAL_FRACTION, LEVERAGE, SIMULATED_BALANCE, MIN_EFFECTIVE_CAPITAL);

            if (trade != null) {
                int count = tradesDone.incrementAndGet();
                tradePersistenceService.saveTradeEntry(trade,
                        0.0, avgCandleVolume, latest.getVolume(),
                        signal == VwapSignal.BUY, 0.0,
                        tradeExecutionService.isPaperTrade(), "VWAP");
                vwapTradeManagement.registerTrade(trade);

                System.out.println(String.format("%s[VWAP-ENTRY] Trade #%d: %s %s @ %.2f | VWAP: %.2f | Dev: %.2f%%%s",
                        GREEN, count, direction, stock, latest.getClose(), vwap,
                        ((latest.getClose() - vwap) / vwap) * 100, RESET));

                if (count >= MAX_TRADES) {
                    System.out.println(YELLOW + "[VWAP-ENTRY] Max VWAP trades (" + MAX_TRADES + ") reached." + RESET);
                }
            }
        }
    }


    // ===================== UTILITIES =====================
    private long computeInitialDelaySeconds() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime = LocalDate.now().atTime(TRADE_START_TIME);

        if (now.isAfter(startTime)) {
            // Already past 12:00 — start at next 5-min slot
            int nextSlot = ((now.getMinute() / 5) + 1) * 5;
            LocalDateTime nextPoll;
            if (nextSlot >= 60)
                nextPoll = now.plusHours(1).withMinute(0).withSecond(1);
            else
                nextPoll = now.withMinute(nextSlot).withSecond(1);
            return Math.max(Duration.between(now, nextPoll).getSeconds(), 0);
        }

        return Math.max(Duration.between(now, startTime).getSeconds(), 0);
    }

    private void cleanupAndExit() {
        System.out.println(YELLOW + "[VWAP-EXIT] VWAP strategy engine stopping..." + RESET);
        printEndOfDaySummary();
        cleanup();
        stopped = true;
        System.out.println(YELLOW + "[VWAP-EXIT] VWAP strategy engine stopped." + RESET);
        // Close app context — VWAP is the last strategy to run
        applicationContext.close();
    }

    private void printEndOfDaySummary() {
        Map<String, String> results = vwapTradeManagement.getTradeResults();
        Map<String, Double> pnls = vwapTradeManagement.getTradePnlPercent();

        System.out.println(CYAN + "==================== VWAP END-OF-DAY SUMMARY ====================" + RESET);
        if (results.isEmpty()) {
            System.out.println(YELLOW + "No VWAP trades executed today." + RESET);
        } else {
            double totalPnl = 0;
            for (Map.Entry<String, String> entry : results.entrySet()) {
                String stock = entry.getKey();
                String reason = entry.getValue();
                double pnl = pnls.getOrDefault(stock, 0.0);
                totalPnl += pnl;
                String color = pnl >= 0 ? GREEN : RED;
                System.out.println(String.format("%s  %s | %s | P&L: %.2f%%%s", color, stock, reason, pnl, RESET));
            }
            String totalColor = totalPnl >= 0 ? GREEN : RED;
            System.out.println(String.format("%s  TOTAL P&L: %.2f%%%s", totalColor, totalPnl, RESET));
        }
        System.out.println(CYAN + "=================================================================" + RESET);
    }

    public boolean isStopped() {
        return stopped;
    }

    @PreDestroy
    public void cleanup() {
        // Close any remaining VWAP trades
        vwapTradeManagement.closeAllTrades("VWAP_EOD_CUTOFF");

        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            System.out.println(YELLOW + "[VWAP-SHUTDOWN] Executor stopped." + RESET);
        }
    }
}
