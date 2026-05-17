# TradingBot — Automated Intraday ORB Strategy for NSE

An automated intraday equity trading bot for Indian NSE markets. Combines **Chartink** (stock screener via Selenium) with **Angel One SmartAPI** (broker API) to execute a **15-Minute Opening Range Breakout (ORB)** strategy with market bias filtering.

---

## Strategy Overview

### 1. Market Bias Filter (NIFTY 50)
- Fetches NIFTY 50 % change at market open
- **Positive bias** (> +0.10%): Only take **bullish** (BUY) trades
- **Negative bias** (< -0.10%): Only take **bearish** (SELL) trades
- **Flat** (within ±0.10%): No trades for the day

### 2. Stock Selection (Chartink Scanner)
- **Positive scan** → Stocks where Open = Low (bullish signal)
- **Negative scan** → Stocks where Open = High (bearish signal)
- Automated login and scraping via Selenium ChromeDriver

### 3. ATR Filter (Always On)
- 14-day ATR lookback
- Only trade stocks with ATR between **1.5% – 3.0%** of price
- Filters out low-volatility (no movement) and high-volatility (too risky) stocks

### 4. Volume Confirmation
- Current candle volume must be ≥ **1.5x** average daily volume
- Confirms institutional participation in the breakout

### 5. Breakout Detection
- Monitors 5-minute candles after 9:35 AM
- **Bullish breakout**: Close above 15-min high
- **Bearish breakout**: Close below 15-min low
- Breakout candle range must be ≤ 0.5% (rejects wide-range bars)

### 6. Retest Confirmation
- After breakout, waits for price to **retest** the breakout level
- Touch tolerance: 0.2% | Fail tolerance: 0.5%
- Only enters after retest confirms support/resistance

### 7. Two-Stage Targets
| Parameter | Value | Description |
|-----------|-------|-------------|
| Stop Loss | 0.70% | Initial SL from entry |
| Target 1 (T1) | 1.05% | 1:1.5 R:R — locks profit, SL moves to T1 level |
| Target 2 (T2) | 2.10% | 1:3 R:R — hard exit, maximum profit |
| Trail Step | 0.20% | After T1 hit, SL trails by 0.20% steps (never below T1 lock) |

### 8. Risk Management
- Max **2 trades** per day
- Trade cutoff at **12:00 PM** (no new entries after)
- Position sizing: 50% of capital × 5x leverage
- Auto-close and shutdown after limits are reached

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.4.5 (CLI app, no web server) |
| Broker API | Angel One SmartAPI SDK |
| Stock Screener | Chartink via Selenium WebDriver |
| Database | SQLite (trade persistence via Spring Data JPA) |
| Build Tool | Maven |

---

## Project Structure

```
src/main/java/com/project/tradingBot/
├── TradingBotApplication.java          # Entry point (CommandLineRunner)
├── Config/
│   ├── SmartApiConfig.java             # Angel One API credentials
│   └── ChartinkConfig.java            # Chartink scanner URLs & credentials
├── models/
│   ├── Candle.java                     # OHLCV candle data
│   ├── ActiveTrade.java                # Active trade state with P&L calculation
│   ├── StockContext.java               # Per-stock context (high/low/ATR/volume)
│   └── Trade.java                      # JPA entity for SQLite persistence
├── repository/
│   └── TradeRepository.java            # Spring Data JPA repository
├── service/
│   ├── StrategyEngine.java             # Orchestrator — init/poll/shutdown + all constants
│   ├── MarketDataService.java          # NIFTY bias, ATR calculation, stock filtering
│   ├── SignalDetectionService.java     # Breakout detection + retest confirmation
│   ├── TradeExecutionService.java      # Position sizing + order placement
│   ├── TradeManagementService.java     # Trailing SL, T1/T2 targets, trade closure
│   ├── TradePersistenceService.java    # SQLite save/update via TradeRepository
│   ├── SmartApiService.java            # Angel One SDK wrapper (login, candles, orders)
│   ├── ChartinkScannerService.java     # Selenium-based Chartink scraper
│   ├── PopulateScanResultService.java  # Downloads & indexes AngelOne scrip master
│   ├── SleepPreventionService.java     # Prevents Windows sleep during trading
│   └── TotpUtilService.java           # TOTP 2FA generation for SmartAPI login
└── util/
    └── ConsoleColors.java              # ANSI color constants for console output
```

---

## Configuration

All settings are in `src/main/resources/application.properties`:

```properties
# Chartink credentials
chartink.loginUrl=https://chartink.com/login
chartink.positiveScanUrl=<your-positive-scan-url>
chartink.negativeScanUrl=<your-negative-scan-url>
chartink.username=<your-chartink-email>
chartink.password=<your-chartink-password>

# Angel One SmartAPI credentials
smartapi.apiKey=<your-api-key>
smartapi.clientId=<your-client-id>
smartapi.password=<your-pin>
smartapi.totpSecret=<your-totp-secret>

# Trading mode (true = paper trading, false = live)
trading.paperTrade=true
```

### Strategy Constants
All strategy parameters are in `StrategyEngine.java` for easy tuning:
```java
NIFTY_BIAS_THRESHOLD = 0.10    // ±0.10% NIFTY threshold
SL_PERCENT = 0.70              // Stop loss
TARGET1_PERCENT = 1.05         // T1 (1:1.5 R:R)
TARGET2_PERCENT = 2.10         // T2 (1:3 R:R)
TRAIL_STEP_PERCENT = 0.20      // Trailing SL step after T1
MAX_TRADES = 2                 // Max trades per day
TRADE_CUTOFF_TIME = 12:00      // No new trades after this
```

---

## Prerequisites

1. **Java 17+** installed
2. **Google Chrome** installed (for Selenium/Chartink scanner)
3. **Angel One SmartAPI** account with:
   - API key registered with your public IP
   - TOTP 2FA enabled
4. **Chartink** account (free tier works)

---

## How to Run

### From Eclipse
1. Import as Maven project
2. Update credentials in `application.properties`
3. Run `TradingBotApplication.java` as Java Application

### From Command Line
```bash
./mvnw spring-boot:run
```

---

## Execution Flow

```
1. Prevent Windows Sleep
2. Login to Angel One SmartAPI (TOTP 2FA)
3. Open Chrome → Login to Chartink → Scrape positive & negative stock lists
4. Download AngelOne scrip master → Build symbol-to-token map
5. Fetch NIFTY 50 % change → Determine market bias
6. Filter stocks by ATR (1.5%–3.0%, 14-day lookback)
7. Start polling loop (every 5 minutes):
   a. Fetch 5-min candles for each stock
   b. Detect breakout above/below 15-min range
   c. Wait for retest confirmation
   d. Execute bracket order (paper or live)
   e. Manage trailing SL → T1 lock → T2 hard exit
8. Auto-shutdown after 2 trades or 12:00 PM cutoff
```

---

## Trade Persistence

All trades are saved to `tradingbot.db` (SQLite) with fields:
- Entry/exit price, quantity, direction
- SL, T1, T2 levels
- ATR %, volume data
- P&L %, exit reason
- Paper trade flag
- Timestamps

---

## Paper Trading vs Live Trading

Set `trading.paperTrade` in `application.properties`:
- **`true`** — Simulates trades with ₹1,00,000 virtual capital. No real orders placed. All analysis, signals, and tracking work identically.
- **`false`** — Places real bracket orders on Angel One. Ensure your API key is registered with your current public IP.

---

## Important Notes

- **Market Hours Only**: The bot is designed for NSE market hours (9:15 AM – 3:30 PM IST). Running after hours will show empty candle data — this is expected.
- **API Rate Limits**: 2-second delay between candle API calls to avoid Angel One rate limiting.
- **IP Binding**: Angel One API key must be registered with your public IP. Error `AG8004` means IP mismatch.
- **Session Expiry**: The bot auto-reauthenticates on session expiry (`AB1004` error).
