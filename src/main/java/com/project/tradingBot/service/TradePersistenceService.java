package com.project.tradingBot.service;

import com.project.tradingBot.models.ActiveTrade;
import com.project.tradingBot.models.Trade;
import com.project.tradingBot.repository.TradeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.project.tradingBot.util.ConsoleColors.*;

/**
 * Handles all database persistence for trades.
 */
@Service
public class TradePersistenceService {

    @Autowired
    private TradeRepository tradeRepository;

    /**
     * Saves a new trade entry to the database and links the entity to the ActiveTrade.
     */
    public void saveTradeEntry(ActiveTrade activeTrade, double atrPercent, double avgVolume,
                               double breakoutVolume, boolean isPositiveDay, double niftyChangePercent,
                               boolean paperTrade) {
        saveTradeEntry(activeTrade, atrPercent, avgVolume, breakoutVolume, isPositiveDay, niftyChangePercent, paperTrade, "ORB");
    }

    /**
     * Saves a new trade entry to the database and links the entity to the ActiveTrade.
     */
    public void saveTradeEntry(ActiveTrade activeTrade, double atrPercent, double avgVolume,
                               double breakoutVolume, boolean isPositiveDay, double niftyChangePercent,
                               boolean paperTrade, String strategyName) {
        try {
            Trade trade = new Trade();
            trade.setTradeDate(LocalDate.now());
            trade.setStock(activeTrade.getStock());
            trade.setDirection(activeTrade.getDirection());
            trade.setMode(paperTrade ? "PAPER" : "LIVE");
            trade.setEntryPrice(activeTrade.getEntryPrice());
            trade.setQuantity(activeTrade.getQuantity());
            trade.setCapitalUsed(activeTrade.getCapitalUsed());
            trade.setStopLoss(activeTrade.getTrailSL());
            trade.setTarget1(activeTrade.getTarget1());
            trade.setTarget2(activeTrade.getTarget2());
            trade.setT1Hit(false);
            trade.setAtrPercent(atrPercent);
            trade.setAvgVolume(avgVolume);
            trade.setBreakoutVolume(breakoutVolume);
            trade.setNiftyBias(isPositiveDay ? "POSITIVE" : "NEGATIVE");
            trade.setNiftyChangePercent(niftyChangePercent);
            trade.setStrategy(strategyName);
            trade.setEntryTime(LocalDateTime.now());

            trade = tradeRepository.save(trade);
            activeTrade.setTradeEntity(trade);
            System.out.println(CYAN + "[DB] Trade saved (id=" + trade.getId() + ") — " + activeTrade.getDirection() + " " + activeTrade.getStock() + " @ " + activeTrade.getEntryPrice() + RESET);
        } catch (Exception e) {
            System.out.println(RED + "[DB] Failed to save trade for " + activeTrade.getStock() + ": " + e.getMessage() + RESET);
        }
    }

    /**
     * Updates a trade with exit details in the database.
     */
    public void updateTradeExit(ActiveTrade activeTrade, double exitPrice, String exitReason, double pnlPercent) {
        try {
            Trade trade = activeTrade.getTradeEntity();
            if (trade == null) {
                System.out.println(YELLOW + "[DB] No trade entity found for " + activeTrade.getStock() + " — skipping DB update." + RESET);
                return;
            }
            trade.setExitPrice(exitPrice);
            trade.setExitReason(exitReason);
            trade.setPnlPercent(pnlPercent);
            trade.setPnlAmount(pnlPercent / 100 * trade.getCapitalUsed());
            trade.setT1Hit(activeTrade.isT1Hit());
            trade.setExitTime(LocalDateTime.now());

            tradeRepository.save(trade);
            System.out.println(String.format("%s[DB] Trade updated (id=%d) — %s %s | Exit: %.2f | P&L: %.2f%% (₹%.2f) | %s%s",
                    CYAN, trade.getId(), trade.getDirection(), activeTrade.getStock(),
                    exitPrice, pnlPercent, trade.getPnlAmount(), exitReason, RESET));
        } catch (Exception e) {
            System.out.println(RED + "[DB] Failed to update trade for " + activeTrade.getStock() + ": " + e.getMessage() + RESET);
        }
    }
}
