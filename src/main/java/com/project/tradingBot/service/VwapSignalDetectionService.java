package com.project.tradingBot.service;

import com.project.tradingBot.models.Candle;
import org.springframework.stereotype.Service;

import static com.project.tradingBot.util.ConsoleColors.*;

/**
 * Detects VWAP mean reversion signals:
 * 1. VWAP deviation >= threshold
 * 2. Volume spike > multiplier × average intraday volume
 * 3. Reversal candle confirmation (hammer for longs, shooting star for shorts)
 */
@Service
public class VwapSignalDetectionService {

    public enum VwapSignal {
        NONE, BUY, SELL
    }

    /**
     * Evaluates whether the current candle represents a valid VWAP mean reversion entry.
     *
     * @param candle          latest 5-min candle
     * @param vwap            current VWAP value
     * @param deviationMin    minimum VWAP deviation % to trigger (e.g., 1.3)
     * @param deviationMax    maximum VWAP deviation % — beyond this is likely trend-driven (e.g., 2.5)
     * @param avgCandleVolume average volume of prior intraday 5-min candles
     * @param volumeMultiplier volume spike threshold (e.g., 1.5)
     * @return BUY if price is below VWAP and reversal confirmed, SELL if above, NONE otherwise
     */
    public VwapSignal evaluate(Candle candle, double vwap, double deviationMin, double deviationMax,
                               double avgCandleVolume, double volumeMultiplier) {

        if (vwap <= 0 || candle.getClose() <= 0) return VwapSignal.NONE;

        double deviationPercent = ((candle.getClose() - vwap) / vwap) * 100;
        double absDeviation = Math.abs(deviationPercent);

        // Check deviation within valid range
        if (absDeviation < deviationMin || absDeviation > deviationMax) {
            return VwapSignal.NONE;
        }

        // Volume spike check
        if (avgCandleVolume > 0 && candle.getVolume() < avgCandleVolume * volumeMultiplier) {
            System.out.println(String.format("%s[VWAP-SIGNAL] Volume too low (%.0f < %.0f × %.1f). Skipping.%s",
                    YELLOW, candle.getVolume(), avgCandleVolume, volumeMultiplier, RESET));
            return VwapSignal.NONE;
        }

        // Reversal candle check
        if (deviationPercent < 0) {
            // Price BELOW VWAP → looking for bullish reversal (hammer) → BUY toward VWAP
            if (isHammer(candle)) {
                System.out.println(String.format("%s[VWAP-SIGNAL] BUY signal: %.2f is %.2f%% below VWAP %.2f | Volume: %.0f (%.1fx avg) | Hammer confirmed%s",
                        GREEN, candle.getClose(), absDeviation, vwap, candle.getVolume(),
                        avgCandleVolume > 0 ? candle.getVolume() / avgCandleVolume : 0, RESET));
                return VwapSignal.BUY;
            }
        } else {
            // Price ABOVE VWAP → looking for bearish reversal (shooting star) → SELL toward VWAP
            if (isShootingStar(candle)) {
                System.out.println(String.format("%s[VWAP-SIGNAL] SELL signal: %.2f is %.2f%% above VWAP %.2f | Volume: %.0f (%.1fx avg) | Shooting star confirmed%s",
                        RED, candle.getClose(), absDeviation, vwap, candle.getVolume(),
                        avgCandleVolume > 0 ? candle.getVolume() / avgCandleVolume : 0, RESET));
                return VwapSignal.SELL;
            }
        }

        System.out.println(String.format("%s[VWAP-SIGNAL] Deviation %.2f%% OK but no reversal candle pattern. Waiting.%s",
                YELLOW, deviationPercent, RESET));
        return VwapSignal.NONE;
    }

    /**
     * Hammer (bullish reversal): lower shadow >= 2× body, upper shadow <= 30% of total range.
     * Indicates selling exhaustion — buyers stepping in.
     */
    private boolean isHammer(Candle c) {
        double body = Math.abs(c.getClose() - c.getOpen());
        double totalRange = c.getHigh() - c.getLow();
        if (totalRange <= 0) return false;

        double lowerShadow = Math.min(c.getOpen(), c.getClose()) - c.getLow();
        double upperShadow = c.getHigh() - Math.max(c.getOpen(), c.getClose());

        return lowerShadow >= 2 * body && upperShadow <= 0.3 * totalRange;
    }

    /**
     * Shooting Star (bearish reversal): upper shadow >= 2× body, lower shadow <= 30% of total range.
     * Indicates buying exhaustion — sellers stepping in.
     */
    private boolean isShootingStar(Candle c) {
        double body = Math.abs(c.getClose() - c.getOpen());
        double totalRange = c.getHigh() - c.getLow();
        if (totalRange <= 0) return false;

        double upperShadow = c.getHigh() - Math.max(c.getOpen(), c.getClose());
        double lowerShadow = Math.min(c.getOpen(), c.getClose()) - c.getLow();

        return upperShadow >= 2 * body && lowerShadow <= 0.3 * totalRange;
    }
}
