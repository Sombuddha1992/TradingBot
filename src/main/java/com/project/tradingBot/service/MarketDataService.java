package com.project.tradingBot.service;

import com.project.tradingBot.models.Candle;
import com.project.tradingBot.models.StockContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.project.tradingBot.util.ConsoleColors.*;

/**
 * Responsible for fetching market data, determining NIFTY bias,
 * and filtering stocks by ATR and volume criteria.
 */
@Service
public class MarketDataService {

    @Autowired
    private SmartApiService smartApiService;

    private final Map<String, StockContext> stockContextMap = new ConcurrentHashMap<>();

    public Map<String, StockContext> getStockContextMap() {
        return stockContextMap;
    }

    /**
     * Determines market bias based on NIFTY 50 % change.
     * @return positive value = bullish, negative = bearish, 0 = flat (skip trading)
     */
    public double fetchNiftyChange() {
        return smartApiService.getNiftyChangePercent();
    }

    /**
     * Fetches 15-min candle + daily candles for ATR/volume, builds StockContext.
     * Returns list of stocks that passed all filters.
     */
    public List<String> initializeStockData(List<String> candidates, double atrMin, double atrMax, int atrLookbackDays) {
        stockContextMap.clear();
        List<String> passedStocks = new ArrayList<>();

        String today = LocalDate.now().toString();
        String from15 = today + " 09:15";
        String to15 = today + " 09:30";
        String atrFrom = LocalDate.now().minusDays(atrLookbackDays + 5).toString();
        String atrTo = LocalDate.now().minusDays(1).toString();

        System.out.println(CYAN + "----------------------------------------------------------------------" + RESET);
        System.out.println(YELLOW + "[INIT] Fetching 15-min candles + ATR for " + candidates.size() + " candidate stocks..." + RESET);

        for (String stock : candidates) {
            boolean success = false;
            int maxRetries = 3;
            int delayMs = 2000;

            for (int attempt = 1; attempt <= maxRetries && !success; attempt++) {
                try {
                    if (attempt > 1) {
                        System.out.println(YELLOW + "[RETRY] Attempt " + attempt + " for " + stock + RESET);
                    }

                    Thread.sleep(delayMs * attempt);

                    List<Candle> candles = smartApiService.getHistoricalCandles(stock, "FIFTEEN_MINUTE", from15, to15);

                    if (candles.isEmpty()) {
                        System.out.println(YELLOW + "[WARN] No 15-min candle data for " + stock + RESET);
                        Thread.sleep(30000);
                        System.out.println(YELLOW + "[AUTH] Reauthenticating before retrying " + stock + RESET);
                        smartApiService.login();
                        continue;
                    }

                    Candle c = candles.get(0);

                    // Fetch daily candles for ATR + average volume
                    List<Candle> dailyCandles = smartApiService.getDailyCandles(stock, atrFrom, atrTo);
                    if (dailyCandles.size() < 5) {
                        System.out.println(YELLOW + "[WARN] Insufficient daily data for ATR calculation: " + stock + RESET);
                        break;
                    }

                    double atr = calculateATR(dailyCandles, Math.min(atrLookbackDays, dailyCandles.size()));
                    double avgClose = dailyCandles.stream().mapToDouble(Candle::getClose).average().orElse(1.0);
                    double atrPercent = (atr / avgClose) * 100;
                    double avgVol = dailyCandles.stream().mapToDouble(Candle::getVolume).average().orElse(0.0);

                    // ATR filter
                    if (atrPercent < atrMin || atrPercent > atrMax) {
                        System.out.println(String.format("%s[FILTER] %s ATR %.2f%% outside [%.1f%%, %.1f%%] → Removed%s",
                                YELLOW, stock, atrPercent, atrMin, atrMax, RESET));
                        break;
                    }

                    // Build context and store
                    StockContext ctx = new StockContext(stock, c.getHigh(), c.getLow());
                    ctx.setAvgVolume(avgVol);
                    ctx.setAtrPercent(atrPercent);
                    stockContextMap.put(stock, ctx);
                    passedStocks.add(stock);

                    double rangePercent = ((c.getHigh() - c.getLow()) / c.getLow()) * 100;
                    System.out.println(String.format("%s[OK]   %-10s | High: %.2f | Low: %.2f | Range: %.2f%% | ATR: %.2f%% | AvgVol: %.0f%s",
                            GREEN, stock, c.getHigh(), c.getLow(), rangePercent, atrPercent, avgVol, RESET));

                    success = true;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.out.println(RED + "[INIT] Interrupted while sleeping: " + ie.getMessage() + RESET);
                    break;
                } catch (Exception e) {
                    handleAuthError(e, stock);
                }
            }

            if (!success && !stockContextMap.containsKey(stock)) {
                System.out.println(RED + "[FAIL] All retries failed for " + stock + " → Skipping." + RESET);
            }
        }

        System.out.println(CYAN + "----------------------------------------------------------------------" + RESET);
        return passedStocks;
    }

    /**
     * Calculates ATR (Average True Range) over the given period.
     */
    public double calculateATR(List<Candle> dailyCandles, int period) {
        if (dailyCandles.size() < 2) return 0.0;

        List<Double> trueRanges = new ArrayList<>();
        for (int i = 1; i < dailyCandles.size(); i++) {
            Candle curr = dailyCandles.get(i);
            Candle prev = dailyCandles.get(i - 1);
            double tr = Math.max(
                    curr.getHigh() - curr.getLow(),
                    Math.max(
                            Math.abs(curr.getHigh() - prev.getClose()),
                            Math.abs(curr.getLow() - prev.getClose())
                    )
            );
            trueRanges.add(tr);
        }

        int start = Math.max(0, trueRanges.size() - period);
        double sum = 0;
        int count = 0;
        for (int i = start; i < trueRanges.size(); i++) {
            sum += trueRanges.get(i);
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }

    private void handleAuthError(Exception e, String stock) {
        String errorMsg = e.getMessage() != null ? e.getMessage() : "";
        if (errorMsg.contains("AB1004") || errorMsg.toLowerCase().contains("unauthorized")
                || errorMsg.contains("session") || errorMsg.contains("token")) {
            System.out.println(YELLOW + "[WARN] Auth issue while fetching " + stock + RESET);
            System.out.println(YELLOW + "[AUTH] Waiting 30s before reauthenticating..." + RESET);
            try {
                Thread.sleep(30000);
                smartApiService.login();
                System.out.println(GREEN + "[AUTH] Reauthentication successful. Retrying..." + RESET);
            } catch (Exception re) {
                System.out.println(RED + "[AUTH] Reauthentication failed: " + re.getMessage() + RESET);
            }
        } else {
            System.out.println(RED + "[INIT] Error fetching candle for " + stock + ". Retrying..." + RESET);
        }
    }
}
