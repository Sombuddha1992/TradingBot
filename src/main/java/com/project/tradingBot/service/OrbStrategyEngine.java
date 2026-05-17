package com.project.tradingBot.service;

import com.project.tradingBot.models.ActiveTrade;
import com.project.tradingBot.models.Candle;
import com.project.tradingBot.models.StockContext;
import com.project.tradingBot.service.SignalDetectionService.Signal;
import com.project.tradingBot.service.SignalDetectionService.SignalResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.project.tradingBot.util.ConsoleColors.*;

/**
 * Orchestrates the trading strategy lifecycle:
 * init → start → poll → detect signals → execute trades → manage positions → shutdown.
 */
@Service
public class OrbStrategyEngine {

    @Autowired private MarketDataService marketDataService;
    @Autowired private SignalDetectionService signalDetectionService;
    @Autowired private TradeExecutionService tradeExecutionService;
    @Autowired private TradeManagementService tradeManagementService;
    @Autowired private TradePersistenceService tradePersistenceService;
    @Autowired private SmartApiService smartApiService;
    private ScheduledExecutorService executor;
    private ExecutorService stockExecutor;
    private List<String> stocksToMonitor = new ArrayList<>();

    private final AtomicInteger tradesDone = new AtomicInteger(0);
    private boolean isPositiveDay;
    private double niftyChangePercent;
    private volatile boolean initialized = false;
    private volatile boolean stopped = false;
    private Runnable onShutdownCallback;

    // ---- Strategy parameters (all in one place for easy tuning) ----
    private static final double NIFTY_BIAS_THRESHOLD = 0.10;
    private static final double SL_PERCENT = 0.70;
    private static final double TARGET1_PERCENT = 1.05;   // 1:1.5 — locks profit
    private static final double TARGET2_PERCENT = 2.10;   // 1:3   — hard exit
    private static final double TRAIL_STEP_PERCENT = 0.20;
    private static final double VOLUME_MULTIPLIER = 1.5;
    private static final double ATR_MIN_PERCENT = 1.5;
    private static final double ATR_MAX_PERCENT = 3.0;
    private static final double BREAKOUT_RANGE_MAX = 0.5;
    private static final LocalTime TRADE_CUTOFF_TIME = LocalTime.of(12, 0);
    private static final int ATR_LOOKBACK_DAYS = 14;
    private static final int MAX_TRADES = 2;
    private static final int POLL_INTERVAL_LIVE = 300;
    private static final int POLL_INTERVAL_PAPER = 60;
    private static final double CAPITAL_FRACTION = 0.5;
    private static final double LEVERAGE = 5.0;
    private static final double SIMULATED_BALANCE = 100000;
    private static final double MIN_EFFECTIVE_CAPITAL = 1000;
    private static final double RETEST_TOUCH_TOLERANCE = 0.002;
    private static final double RETEST_FAIL_TOLERANCE = 0.005;


    // ===================== INIT =====================
    public void init(List<String> positiveStocks, List<String> negativeStocks) {
        try {
            niftyChangePercent = marketDataService.fetchNiftyChange();

            System.out.println(CYAN + "===================== [INIT] STRATEGY INITIALIZATION =====================" + RESET);
            System.out.println(String.format("%s→ NIFTY %% Change: %.2f%%%s", YELLOW, niftyChangePercent, RESET));
            System.out.println(String.format("%s→ Bias Threshold: ±%.2f%%%s", YELLOW, NIFTY_BIAS_THRESHOLD, RESET));

            List<String> candidates;
            if (niftyChangePercent > NIFTY_BIAS_THRESHOLD) {
                candidates = positiveStocks;
                isPositiveDay = true;
                System.out.println(GREEN + "[INIT] Positive Market Bias → Monitoring Positive Stocks." + RESET);
            } else if (niftyChangePercent < -NIFTY_BIAS_THRESHOLD) {
                candidates = negativeStocks;
                isPositiveDay = false;
                System.out.println(RED + "[INIT] Negative Market Bias → Monitoring Negative Stocks." + RESET);
            } else {
                System.out.println(YELLOW + "[INIT] NIFTY within ±" + NIFTY_BIAS_THRESHOLD + "% — flat market, not trading today." + RESET);
                return;
            }

            List<String> filtered = marketDataService.initializeStockData(candidates, ATR_MIN_PERCENT, ATR_MAX_PERCENT, ATR_LOOKBACK_DAYS);
            stocksToMonitor = new CopyOnWriteArrayList<>(filtered);

            if (!stocksToMonitor.isEmpty()) {
                System.out.println(GREEN + "[INIT] Final Stocks to Monitor (" + stocksToMonitor.size() + "):" + RESET);
                Map<String, StockContext> ctxMap = marketDataService.getStockContextMap();
                for (String stock : stocksToMonitor) {
                    StockContext ctx = ctxMap.get(stock);
                    System.out.println(String.format("%s   → %s (ATR: %.2f%%)%s", GREEN, stock,
                            ctx != null ? ctx.getAtrPercent() : 0.0, RESET));
                }
            } else {
                System.out.println(RED + "[INIT] No stocks left to monitor after filtering." + RESET);
            }

            System.out.println(CYAN + "======================================================================" + RESET);
            initialized = true;

        } catch (Exception e) {
            System.out.println(RED + "[INIT] Initialization failed: " + e.getMessage() + RESET);
            e.printStackTrace();
        }
    }


    // ===================== START =====================
    public void start() {
        if (!initialized) {
            System.out.println(RED + "[START] Strategy not initialized. Call init() first." + RESET);
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("OrbStrategyEngine-Poller");
            t.setDaemon(false);
            return t;
        });

        long initialDelay = computeInitialDelaySeconds();
        System.out.println(CYAN + "[START] Strategy will start polling after " + initialDelay + " seconds." + RESET);
        System.out.println(CYAN + "[START] Trade cutoff time: " + TRADE_CUTOFF_TIME + RESET);
        System.out.println(String.format("%s[START] SL: %.2f%% | T1: %.2f%% (lock profit) | T2: %.2f%% (hard exit) | Trail step: %.2f%%%s",
                CYAN, SL_PERCENT, TARGET1_PERCENT, TARGET2_PERCENT, TRAIL_STEP_PERCENT, RESET));

        boolean paperTrade = tradeExecutionService.isPaperTrade();
        System.out.println(paperTrade
                ? YELLOW + "[START] *** PAPER TRADE MODE — 60s polling when trades active, 300s for scanning ***" + RESET
                : RED + "[START] *** LIVE TRADE MODE — real orders WILL be placed ***" + RESET);

        // Use dynamic scheduling: 60s when paper trades active, 300s for signal scanning
        executor.schedule(() -> pollAndReschedule(), initialDelay, TimeUnit.SECONDS);

        keepAliveThread();
    }

    /**
     * Runs one poll cycle, then schedules the next with dynamic interval:
     * - 60s if paper trade mode AND active trades exist (precise SL/target monitoring)
     * - 300s otherwise (5-min candle-based signal scanning)
     */
    private void pollAndReschedule() {
        try {
            pollStocks();
            tradeManagementService.manageTrailingSL(TARGET1_PERCENT, TRAIL_STEP_PERCENT);
        } catch (Exception e) {
            System.out.println(RED + "[SCHEDULE] Uncaught exception in scheduled task: " + e.getMessage() + RESET);
            e.printStackTrace();
        }

        if (stopped) return;

        boolean hasActiveTrades = !tradeManagementService.getActiveTrades().isEmpty();
        int nextInterval = (tradeExecutionService.isPaperTrade() && hasActiveTrades)
                ? POLL_INTERVAL_PAPER : POLL_INTERVAL_LIVE;
        System.out.println(CYAN + "[SCHEDULE] Next poll in " + nextInterval + "s"
                + (hasActiveTrades ? " (monitoring active trades)" : " (scanning for signals)") + RESET);
        executor.schedule(() -> pollAndReschedule(), nextInterval, TimeUnit.SECONDS);
    }


    // ===================== POLL =====================
    private void pollStocks() {
        System.out.println(CYAN + "[POLL] --------------------------------------------------------------" + RESET);
        System.out.println(YELLOW + "[POLL] Polling started at " + LocalTime.now() + " | Trades done: " + tradesDone.get() + RESET);

        List<String> activeStocks = tradeManagementService.getActiveTradeStocks();

        if (tradesDone.get() >= MAX_TRADES) {
            System.out.println(RED + "[POLL] Max trades reached. Stopping strategy." + RESET);
            cleanupAndExit();
            return;
        }

        if (LocalTime.now().isAfter(TRADE_CUTOFF_TIME) && activeStocks.isEmpty()) {
            System.out.println(YELLOW + "[POLL] Past " + TRADE_CUTOFF_TIME + " with no active trades. Stopping." + RESET);
            cleanupAndExit();
            return;
        }

        if (stocksToMonitor == null || stocksToMonitor.isEmpty()) {
            if (activeStocks.isEmpty()) {
                System.out.println(RED + "[POLL] No stocks to monitor and no active trades. Exiting." + RESET);
                cleanupAndExit();
                return;
            }
            System.out.println(YELLOW + "[POLL] No new stocks to scan, but active trades remain. Monitoring..." + RESET);
            return;
        }

        if (LocalTime.now().isAfter(TRADE_CUTOFF_TIME)) {
            System.out.println(YELLOW + "[POLL] Past " + TRADE_CUTOFF_TIME + " — only managing existing trades, no new entries." + RESET);
            return;
        }

        if (stockExecutor == null || stockExecutor.isShutdown()) {
            stockExecutor = Executors.newFixedThreadPool(Math.min(10, Math.max(5, stocksToMonitor.size())));
        }

        List<Future<?>> futures = new ArrayList<>();

        for (String stock : new ArrayList<>(stocksToMonitor)) {
            futures.add(stockExecutor.submit(() -> processStock(stock)));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) {
                System.out.println(RED + "[POLL] Exception while processing stock: " + e.getMessage() + RESET);
            }
        }
        System.out.println(CYAN + "[POLL] Cycle completed for all stocks." + RESET);
    }


    // ===================== PROCESS STOCK =====================
    private void processStock(String stock) {
        try {
            System.out.println(YELLOW + "[POLL] Checking " + stock + RESET);
            String[] window = getAligned5MinWindow();

            List<Candle> candles;
            try {
                candles = smartApiService.getHistoricalCandles(stock, "FIVE_MINUTE", window[0], window[1]);
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                System.out.println(RED + "[ERROR] Failed to fetch candles for " + stock + ": " + msg + RESET);
                if (msg.contains("session") || msg.contains("token") || msg.contains("unauthorized") || msg.contains("AB1004")) {
                    System.out.println(YELLOW + "[AUTH] Session expired → Attempting reauthentication..." + RESET);
                    smartApiService.login();
                    Thread.sleep(2000);
                    candles = smartApiService.getHistoricalCandles(stock, "FIVE_MINUTE", window[0], window[1]);
                } else {
                    throw ex;
                }
            }

            if (candles.isEmpty()) {
                System.out.println(YELLOW + "[POLL] No candle data for " + stock + RESET);
                return;
            }

            Candle c = candles.get(0);
            double rangePercent = ((c.getHigh() - c.getLow()) / c.getLow()) * 100;
            System.out.println(String.format("%s[POLL] %-10s | O:%.2f H:%.2f L:%.2f C:%.2f | Vol:%.0f | Range: %.2f%%%s",
                    CYAN, stock, c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume(), rangePercent, RESET));

            StockContext ctx = marketDataService.getStockContextMap().get(stock);
            if (ctx == null) {
                System.out.println(YELLOW + "[POLL] No context found for " + stock + " — skipping." + RESET);
                return;
            }

            // Check pending retest first
            if (signalDetectionService.hasPendingRetest(stock)) {
                SignalResult result = signalDetectionService.evaluateRetest(stock, c, RETEST_TOUCH_TOLERANCE, RETEST_FAIL_TOLERANCE);
                handleSignalResult(stock, c, result);
                return;
            }

            // Evaluate for new breakout
            if (isPositiveDay) {
                System.out.println(GREEN + "[POLL] Positive Day → Looking for Bullish Breakouts." + RESET);
            } else {
                System.out.println(RED + "[POLL] Negative Day → Looking for Bearish Breakdowns." + RESET);
            }

            SignalResult result = signalDetectionService.evaluateBreakout(stock, c, ctx, isPositiveDay, VOLUME_MULTIPLIER, BREAKOUT_RANGE_MAX);
            handleSignalResult(stock, c, result);

        } catch (Exception e) {
            System.out.println(RED + "[ERROR] Exception processing " + stock + ": " + e.getMessage() + RESET);
        }
    }

    private void handleSignalResult(String stock, Candle c, SignalResult result) {
        switch (result.getSignal()) {
            case RETEST_CONFIRMED:
                synchronized (this) {
                    if (tradesDone.get() < MAX_TRADES) {
                        ActiveTrade trade = tradeExecutionService.executeOrder(
                                stock, c, result.getDirection(),
                                SL_PERCENT, TARGET1_PERCENT, TARGET2_PERCENT,
                                CAPITAL_FRACTION, LEVERAGE, SIMULATED_BALANCE, MIN_EFFECTIVE_CAPITAL);
                        if (trade != null) {
                            int count = tradesDone.incrementAndGet();
                            StockContext ctx = marketDataService.getStockContextMap().get(stock);
                            tradePersistenceService.saveTradeEntry(trade,
                                    ctx != null ? ctx.getAtrPercent() : 0.0,
                                    ctx != null ? ctx.getAvgVolume() : 0.0,
                                    c.getVolume(), isPositiveDay, niftyChangePercent,
                                    tradeExecutionService.isPaperTrade());
                            tradeManagementService.registerTrade(trade);

                            System.out.println(GREEN + "[ENTRY] Trade #" + count + " placed successfully." + RESET);
                            if (count >= MAX_TRADES) {
                                System.out.println(RED + "[ENTRY] Max trades (" + MAX_TRADES + ") reached. No more new trades." + RESET);
                            }
                            stocksToMonitor.remove(stock);
                        }
                    }
                }
                break;
            case INVALIDATED:
            case RETEST_FAILED:
                stocksToMonitor.remove(stock);
                break;
            case BREAKOUT_PENDING:
            case WAITING_RETEST:
            case NONE:
            default:
                break;
        }
    }


    // ===================== UTILITIES =====================
    private String[] getAligned5MinWindow() {
        LocalDateTime now = LocalDateTime.now();
        int flooredMinute = (now.getMinute() / 5) * 5;
        LocalDateTime to = now.withMinute(flooredMinute).withSecond(0).withNano(0);
        LocalDateTime from = to.minusMinutes(5);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return new String[]{from.format(fmt), to.format(fmt)};
    }

    private long computeInitialDelaySeconds() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime firstPoll = LocalDate.now().atTime(9, 35, 1);
        if (now.isAfter(firstPoll)) {
            int nextSlot = ((now.getMinute() / 5) + 1) * 5;
            if (nextSlot >= 60)
                firstPoll = now.plusHours(1).withMinute(0).withSecond(1);
            else
                firstPoll = now.withMinute(nextSlot).withSecond(1);
        }
        return Math.max(Duration.between(now, firstPoll).getSeconds(), 0);
    }

    private void keepAliveThread() {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(60000);
                    System.out.println(CYAN + "[KEEP-ALIVE] Strategy running... Active trades: " +
                            tradeManagementService.getActiveTradeStocks().size() + RESET);
                }
            } catch (InterruptedException e) {
                System.out.println(RED + "[KEEP-ALIVE] Thread interrupted. Exiting..." + RESET);
            }
        });
        t.setDaemon(true);
        t.setName("OrbStrategyEngine-KeepAlive");
        t.start();
    }

    private void shutdownExecutor() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            System.out.println(YELLOW + "[SHUTDOWN] Executor stopped." + RESET);
        }
    }

    /**
     * Sets a callback to be invoked when this engine stops (for coordinating with other engines).
     */
    public void setOnShutdownCallback(Runnable callback) {
        this.onShutdownCallback = callback;
    }

    public boolean isStopped() {
        return stopped;
    }

    private void cleanupAndExit() {
        System.out.println(YELLOW + "[ORB-EXIT] ORB strategy engine stopping..." + RESET);
        cleanup();
        stopped = true;
        System.out.println(YELLOW + "[ORB-EXIT] ORB strategy engine stopped." + RESET);
        if (onShutdownCallback != null) {
            onShutdownCallback.run();
        }
    }

    @PreDestroy
    public void cleanup() {
        shutdownExecutor();
        if (stockExecutor != null && !stockExecutor.isShutdown()) {
            stockExecutor.shutdown();
        }
    }
}

