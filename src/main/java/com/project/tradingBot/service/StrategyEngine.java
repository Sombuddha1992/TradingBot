package com.project.tradingBot.service;

import com.project.tradingBot.models.Candle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Service
public class StrategyEngine {

    @Autowired
    private SmartApiService smartApiService;

    private ScheduledExecutorService executor;
    private List<String> stocksToMonitor = new ArrayList<>();

    private final Map<String, Double> stock15MinHighs = new ConcurrentHashMap<>();
    private final Map<String, Double> stock15MinLows = new ConcurrentHashMap<>();
    private int tradesDone = 0;
    private static final int MAX_TRADES = 20;

    private boolean isPositiveDay;
    private volatile boolean initialized = false;
    
    // ---------------------- ANSI COLORS ----------------------
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";


   // ---------------------- INIT ----------------------
	public void init(List<String> positiveStocks, List<String> negativeStocks) {
	    try {
	        double niftyChange = smartApiService.getNiftyChangePercent();
	
	        System.out.println(CYAN + "\n===================== [INIT] STRATEGY INITIALIZATION =====================" + RESET);
	        System.out.printf(YELLOW + "→ NIFTY %% Change: %.2f%%%n" + RESET, niftyChange);
	
	        // Determine bias
	        if (niftyChange > 0.04) {
	            stocksToMonitor = new CopyOnWriteArrayList<>(positiveStocks);
	            isPositiveDay = true;
	            System.out.println(GREEN + "[INIT] Positive Market Bias → Monitoring Positive Stocks." + RESET);
	        } else if (niftyChange < -0.04) {
	            stocksToMonitor = new CopyOnWriteArrayList<>(negativeStocks);
	            isPositiveDay = false;
	            System.out.println(RED + "[INIT] Negative Market Bias → Monitoring Negative Stocks." + RESET);
	        } else {
	            System.out.println(YELLOW + "[INIT] NIFTY flat; not trading today." + RESET);
	            return;
	        }
	
	        String today = LocalDate.now().toString();
	        String from = today + " 09:15";
	        String to = today + " 09:30";
	
	        System.out.println(CYAN + "----------------------------------------------------------------------" + RESET);
	        System.out.println(YELLOW + String.format("[INIT] Fetching 15-min candles for %d candidate stocks...", stocksToMonitor.size()) + RESET);
	
	        for (String stock : new ArrayList<>(stocksToMonitor)) { // copy to avoid concurrent modification
	            boolean success = false;
	            int maxRetries = 3;
	            int delayMs = 2000;
	
	            for (int attempt = 1; attempt <= maxRetries && !success; attempt++) {
	                try {
	                    if (attempt > 1) {
	                        System.out.println(YELLOW + String.format("[RETRY] Attempt %d for %s", attempt, stock) + RESET);
	                    }
	
	                    Thread.sleep(delayMs * attempt); // exponential backoff
	                    Thread.sleep(60000);
	                    
	                    List<Candle> candles = smartApiService.getHistoricalCandles(stock, "FIFTEEN_MINUTE", from, to);
	
	                    if (candles.isEmpty()) {
	                        System.out.println(YELLOW + "[WARN] No 15-min candle data for " + stock + RESET);
	                        break; // no point retrying if API returned empty
	                    }
	
	                    Candle c = candles.get(0);
	                    double rangePercent = ((c.getHigh() - c.getLow()) / c.getLow()) * 100;
	
//	                    if (rangePercent > 1.15) {
//	                        System.out.println(String.format(
//	                                RED + "[SKIP] %-10s | Range: %.2f%% > 1.15%% (Too Volatile) → Removed." + RESET,
//	                                stock, rangePercent
//	                        ));
//	                        stocksToMonitor.remove(stock);
//	                        success = true; // skip further retries
//	                        continue;
//	                    }
	
	                    stock15MinHighs.put(stock, c.getHigh());
	                    stock15MinLows.put(stock, c.getLow());
	                    System.out.println(String.format(
	                            GREEN + "[OK]   %-10s | High: %.2f | Low: %.2f | Range: %.2f%%" + RESET,
	                            stock, c.getHigh(), c.getLow(), rangePercent
	                    ));
	
	                    success = true; // successful fetch
	                } catch (InterruptedException ie) {
	                    Thread.currentThread().interrupt();
	                    System.err.println(RED + "[INIT] Interrupted while sleeping: " + ie.getMessage() + RESET);
	                    break;
	                } catch (Exception e) {
	                    System.err.println(RED + String.format("[INIT] Error fetching 15-min candle for %s: %s", stock, e.getMessage()) + RESET);
	                    // will retry automatically
	                }
	            }
	
	            if (!success) {
	                System.err.println(RED + "[FAIL] All retries failed for " + stock + " → Removing from monitoring list." + RESET);
	                stocksToMonitor.remove(stock);
	            }
	        }
	
	        System.out.println(CYAN + "----------------------------------------------------------------------" + RESET);
	
	        if (!stocksToMonitor.isEmpty()) {
	            System.out.println(GREEN + "[INIT] Final Stocks to Monitor (" + stocksToMonitor.size() + "):" + RESET);
	            for (String stock : stocksToMonitor)
	                System.out.println(GREEN + "   → " + stock + RESET);
	        } else {
	            System.out.println(RED + "[INIT] No stocks left to monitor after filtering." + RESET);
	        }
	
	        System.out.println(CYAN + "======================================================================\n" + RESET);
	        initialized = true;
	
	    } catch (Exception e) {
	        System.err.println(RED + "[INIT] Initialization failed: " + e.getMessage() + RESET);
	        e.printStackTrace();
	    }
	}



    // ---------------------- START ----------------------
    public void start() {
        if (!initialized) {
            System.err.println(RED + "[START] Strategy not initialized. Call init() first." + RESET);
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("StrategyEngine-Poller");
            t.setDaemon(false);
            return t;
        });

        long initialDelay = computeInitialDelaySeconds();
        System.out.println(CYAN + "[START] Strategy will start polling after " + initialDelay + " seconds." + RESET);

        executor.scheduleAtFixedRate(() -> {
            try {
                pollStocks();
            } catch (Exception e) {
                System.err.println(RED + "[SCHEDULE] Uncaught exception in scheduled task: " + e.getMessage() + RESET);
                e.printStackTrace();
            }
        }, initialDelay, 300, TimeUnit.SECONDS);

        keepAliveThread();
    }


    // ---------------------- POLL ----------------------
    private void pollStocks() {
        System.out.println(CYAN + "\n[POLL] --------------------------------------------------------------" + RESET);
        System.out.println(YELLOW + "[POLL] Polling started. Trades done: " + tradesDone + RESET);

        if (tradesDone >= MAX_TRADES) {
            System.out.println(RED + "[POLL] Max trades reached. Stopping strategy." + RESET);
            cleanupAndExit();
            return;
        }

        if (stocksToMonitor == null || stocksToMonitor.isEmpty()) {
            System.out.println(RED + "[POLL] No stocks left to monitor. Exiting." + RESET);
            cleanupAndExit();
            return;
        }

        ExecutorService stockExecutor = Executors.newFixedThreadPool(Math.max(5, stocksToMonitor.size()));
        List<Future<?>> futures = new ArrayList<>();

        for (String stock : new ArrayList<>(stocksToMonitor)) {
            futures.add(stockExecutor.submit(() -> processStock(stock)));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                System.err.println(RED + "[POLL] Exception while processing stock: " + e.getMessage() + RESET);
            }
        }

        stockExecutor.shutdown();
        System.out.println(CYAN + "[POLL] Cycle completed for all stocks." + RESET);
    }


    // ---------------------- PROCESS EACH STOCK ----------------------
    private void processStock(String stock) {
        try {
            System.out.println(YELLOW + "[POLL] Checking " + stock + RESET);
            String[] window = getAligned5MinWindow();
            String from = window[0];
            String to = window[1];

            List<Candle> candles;
            try {
                // --- Fetch Latest Candle ---
                candles = smartApiService.getHistoricalCandles(stock, "FIVE_MINUTE", from, to);
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                System.err.println(RED + "[ERROR] Failed to fetch candles for " + stock + ": " + msg + RESET);

                // --- Handle token expiry / session invalidation ---
                if (msg.contains("session") || msg.contains("token") || msg.contains("unauthorized")) {
                    System.out.println(YELLOW + "[AUTH] Session expired → Attempting reauthentication..." + RESET);
                    smartApiService.login(); // re-login or regenerate token
                    Thread.sleep(2000);
                    System.out.println(GREEN + "[AUTH] Reauthentication successful. Retrying candle fetch..." + RESET);
                    candles = smartApiService.getHistoricalCandles(stock, "FIVE_MINUTE", from, to);
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

            System.out.printf(CYAN + "[POLL] %-10s | O:%.2f H:%.2f L:%.2f C:%.2f | Range: %.2f%%%n" + RESET,
                    stock, c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), rangePercent);

            if (isPositiveDay) {
                System.out.println(GREEN + "[POLL] Positive Day → Looking for Bullish Breakouts." + RESET);
                if (c.getClose() > stock15MinHighs.get(stock) && rangePercent <= 0.5) {
                    synchronized (this) {
                        if (tradesDone < MAX_TRADES) {
                        	 System.out.println(GREEN + "[TRADE] EXECUTE TRADE FOR " + stock +
                                     " | Reason: Close " + c.getClose() + " > 15-min High " + stock15MinHighs.get(stock) +
                                     " and Range " + String.format("%.2f", rangePercent) + "% <= 0.5%" + RESET);
                             
                            executeTrade(stock, c);
                            stocksToMonitor.remove(stock);
                        }
                    }
                } else if (c.getLow() < stock15MinLows.get(stock)) {
                    stocksToMonitor.remove(stock);
                    System.out.println(RED + "[EXIT] " + stock + " broke low → Removed from watchlist." + RESET);
                }

            } else {
                System.out.println(RED + "[POLL] Negative Day → Looking for Bearish Breakdowns." + RESET);
                if (c.getClose() < stock15MinLows.get(stock) && rangePercent <= 0.5) {
                    synchronized (this) {
                        if (tradesDone < MAX_TRADES) {
                        	System.out.println(GREEN + "[TRADE] EXECUTE TRADE FOR " + stock + 
                                    " | Reason: Close " + c.getClose() + " < 15-min Low " + stock15MinLows.get(stock) + 
                                    " and Range " + String.format("%.2f", rangePercent) + "% <= 0.5%" + RESET);
                            
                            executeTrade(stock, c);
                            stocksToMonitor.remove(stock);
                        }
                    }
                } else if (c.getHigh() > stock15MinHighs.get(stock)) {
                    stocksToMonitor.remove(stock);
                    System.out.println(YELLOW + "[EXIT] " + stock + " reversed → Removed from watchlist." + RESET);
                }
            }

        } catch (Exception e) {
            System.err.println(RED + "[ERROR] Exception processing " + stock + ": " + e.getMessage() + RESET);
        }
    }


    // ---------------------- TRADE EXECUTION ----------------------
    private void executeTrade(String stock, Candle c) {
        try {
            System.out.println(GREEN + "[ENTRY] Entry condition met for " + stock + RESET);

            double balance = smartApiService.getBalance();
            if (balance <= 0) {
                System.err.println(RED + "[ENTRY] Balance unavailable or zero — skipping trade." + RESET);
                return;
            }

            double capital = balance * 0.5;
            double leverage = 5.0;
            double effectiveCapital = capital * leverage;
            double marketPrice = c.getHigh();

            int qty = (int) (effectiveCapital / marketPrice);
            if (qty <= 0 || effectiveCapital < 1000) {
                System.err.println(RED + String.format("[ENTRY] Skipping %s | Invalid Qty=%d | Cap=%.2f", stock, qty, effectiveCapital) + RESET);
                return;
            }

            double sl = marketPrice * 0.995;
            double tgt = marketPrice * 1.0075;

            System.out.printf(GREEN + "[ENTRY] Placing order: %s | Qty=%d | Price=%.2f | SL=%.2f | TGT=%.2f | Leverage=%.1fx%n" + RESET,
                    stock, qty, marketPrice, sl, tgt, leverage);

            //boolean placed = smartApiService.placeBracketOrder(stock, "BUY", qty, marketPrice, sl, tgt);

            boolean placed = true;
            
            if (placed) {
                tradesDone++;
                System.out.println(GREEN + "[ENTRY] Trade placed successfully. Total trades: " + tradesDone + RESET);
            } else {
                System.err.println(RED + "[ENTRY] Order failed for " + stock + RESET);
            }

        } catch (Exception e) {
            System.err.println(RED + "[ENTRY] Trade execution error for " + stock + ": " + e.getMessage() + RESET);
        }
    }


    // ---------------------- UTILITIES ----------------------
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
                    System.out.println(CYAN + "[KEEP-ALIVE] Strategy running..." + RESET);
                }
            } catch (InterruptedException e) {
                System.out.println(RED + "[KEEP-ALIVE] Thread interrupted. Exiting..." + RESET);
            }
        });
        t.setDaemon(false);
        t.setName("StrategyEngine-KeepAlive");
        t.start();
    }

    private void shutdownExecutor() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            System.out.println(YELLOW + "[SHUTDOWN] Executor stopped." + RESET);
        }
    }

    private void cleanupAndExit() {
        System.out.println(RED + "[EXIT] Performing cleanup before exit..." + RESET);
        cleanup();
        System.out.println(RED + "[EXIT] Application exiting." + RESET);
        System.exit(1);
    }

    @PreDestroy
    public void cleanup() {
        shutdownExecutor();
    }
}
