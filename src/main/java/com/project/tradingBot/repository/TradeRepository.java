package com.project.tradingBot.repository;

import com.project.tradingBot.models.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findByTradeDate(LocalDate tradeDate);

    List<Trade> findByTradeDateAndStock(LocalDate tradeDate, String stock);

    List<Trade> findByTradeDateBetween(LocalDate from, LocalDate to);
}
