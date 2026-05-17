package com.project.tradingBot.service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.models.OrderParams;
import com.angelbroking.smartapi.models.User;
import com.project.tradingBot.Config.SmartApiConfig;
import com.project.tradingBot.models.Candle;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.project.tradingBot.util.ConsoleColors.*;

@Service
public class SmartApiService {

    @Autowired
    private SmartApiConfig cfg;
    @Autowired
    private TotpUtilService totpUtilService;

    private volatile SmartConnect smartConnect;

    public void login() {
        try {
            if (smartConnect != null) {
                System.out.println("Reauthenticating SmartAPI session...");
                smartConnect = null;
                Thread.sleep(500);
            }

            System.out.println("Logging in to SmartAPI...");

            smartConnect = new SmartConnect();
            smartConnect.setApiKey(cfg.getApiKey());
            smartConnect.setSessionExpiryHook(() -> System.out.println("SmartAPI session expired"));

            String otp = totpUtilService.generateTotp(cfg.getTotpSecret());
            User user = smartConnect.generateSession(cfg.getClientId(), cfg.getPassword(), otp);

            if (user == null || user.getAccessToken() == null) {
                smartConnect = null;
                throw new RuntimeException("SmartAPI login failed: user session is null. Verify credentials in application.properties.");
            }

            smartConnect.setAccessToken(user.getAccessToken());
            smartConnect.setUserId(user.getUserId());
            System.out.println(GREEN + "SmartAPI login successful. User ID: " + user.getUserId() + RESET);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            smartConnect = null;
            throw new RuntimeException("SmartAPI login failed", e);
        }
    }

    public double getBalance() {
        try {
            JSONObject rms = smartConnect.getRMS();
            return rms.optDouble("net", 0.0);
        } catch (Exception e) {
            System.out.println("Failed to fetch balance: " + e.getMessage());
            return 0.0;
        }
    }

    public double getNiftyChangePercent() {
        try {
            JSONObject ltp = smartConnect.getLTP("NSE", "NIFTY 50", "99926000");

            if (ltp == null) {
                System.out.println(RED + "Failed to fetch NIFTY LTP — API returned null. Check if API key is valid." + RESET);
                return 0.0;
            }

            double close = ltp.optDouble("close", 0.0);
            double lastPrice = ltp.optDouble("ltp", 0.0);

            if (close == 0.0) return 0.0;
            return ((lastPrice - close) / close) * 100;
        } catch (Exception e) {
            System.out.println(RED + "Error fetching NIFTY change: " + e.getMessage() + RESET);
            return 0.0;
        }
    }

    /**
     * Fetches the current India VIX value.
     * @return India VIX value, or -1 if fetch fails
     */
    public double getIndiaVix() {
        try {
            JSONObject ltp = smartConnect.getLTP("NSE", "India VIX", "99926004");
            if (ltp == null) {
                System.out.println(RED + "Failed to fetch India VIX — API returned null." + RESET);
                return -1;
            }
            return ltp.optDouble("ltp", -1);
        } catch (Exception e) {
            System.out.println(RED + "Error fetching India VIX: " + e.getMessage() + RESET);
            return -1;
        }
    }

    public synchronized List<Candle> getHistoricalCandles(String symbol, String interval, String fromDate, String toDate) {
        List<Candle> candles = new ArrayList<>();

        try {
            String symbolWithEQ = symbol + "-EQ";
            String token = PopulateScanResultService.getMasterEquitiesMap().get(symbolWithEQ);
            if (token == null) {
                System.out.println("Token not found for symbol: " + symbolWithEQ);
                return candles;
            }

            JSONObject payload = new JSONObject();
            payload.put("exchange", "NSE");
            payload.put("symboltoken", token);
            payload.put("interval", interval);
            payload.put("fromdate", fromDate);
            payload.put("todate", toDate);

            JSONArray data = smartConnect.candleData(payload);
            if (data == null) {
                System.out.println("candleData returned null for " + symbol);
                return candles;
            }

            for (int i = 0; i < data.length(); i++) {
                JSONArray arr = data.getJSONArray(i);
                Candle c = new Candle();
                c.setDatetime(arr.getString(0));
                c.setOpen(arr.getDouble(1));
                c.setHigh(arr.getDouble(2));
                c.setLow(arr.getDouble(3));
                c.setClose(arr.getDouble(4));
                c.setVolume(arr.getDouble(5));
                candles.add(c);
            }

            Thread.sleep(2000); // throttle to avoid API rate limits

        } catch (Exception e) {
            System.out.println("Error fetching candles for " + symbol + ": " + e.getMessage());
        }

        return candles;
    }

    public synchronized List<Candle> getDailyCandles(String symbol, String fromDate, String toDate) {
        List<Candle> candles = new ArrayList<>();
        try {
            String symbolWithEQ = symbol + "-EQ";
            String token = PopulateScanResultService.getMasterEquitiesMap().get(symbolWithEQ);
            if (token == null) {
                System.out.println("Token not found for symbol: " + symbolWithEQ);
                return candles;
            }

            JSONObject payload = new JSONObject();
            payload.put("exchange", "NSE");
            payload.put("symboltoken", token);
            payload.put("interval", "ONE_DAY");
            payload.put("fromdate", fromDate);
            payload.put("todate", toDate);

            JSONArray data = smartConnect.candleData(payload);
            if (data == null) {
                System.out.println("Daily candleData returned null for " + symbol);
                return candles;
            }

            for (int i = 0; i < data.length(); i++) {
                JSONArray arr = data.getJSONArray(i);
                Candle c = new Candle();
                c.setDatetime(arr.getString(0));
                c.setOpen(arr.getDouble(1));
                c.setHigh(arr.getDouble(2));
                c.setLow(arr.getDouble(3));
                c.setClose(arr.getDouble(4));
                c.setVolume(arr.getDouble(5));
                candles.add(c);
            }
            Thread.sleep(2000);
        } catch (Exception e) {
            System.out.println("Error fetching daily candles for " + symbol + ": " + e.getMessage());
        }
        return candles;
    }

    public double getLTP(String symbol) {
        try {
            String symbolWithEQ = symbol + "-EQ";
            String token = PopulateScanResultService.getMasterEquitiesMap().get(symbolWithEQ);
            if (token == null) return 0.0;

            JSONObject ltp = smartConnect.getLTP("NSE", symbolWithEQ, token);
            if (ltp == null) return 0.0;
            return ltp.optDouble("ltp", 0.0);
        } catch (Exception e) {
            System.out.println("Error fetching LTP for " + symbol + ": " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Places a bracket order on Angel One.
     * @param stoplossOffset SL offset from entry price (not absolute)
     * @param targetOffset   Target offset from entry price (not absolute)
     */
    public boolean placeBracketOrder(String tradingSymbol, String transactionType,
                                     int quantity, double price, double stoplossOffset, double targetOffset) {
        try {
            String symbolWithEQ = tradingSymbol + "-EQ";
            String token = PopulateScanResultService.getMasterEquitiesMap().get(symbolWithEQ);
            if (token == null) {
                System.out.println("Token not found for " + symbolWithEQ + " — cannot place order.");
                return false;
            }

            OrderParams params = new OrderParams();
            params.variety = "ROBO";
            params.quantity = quantity;
            params.symboltoken = token;
            params.tradingsymbol = tradingSymbol;
            params.transactiontype = transactionType;
            params.exchange = "NSE";
            params.ordertype = "LIMIT";
            params.producttype = "BO";
            params.duration = "DAY";
            params.price = price;
            params.stoploss = String.valueOf(Math.round(stoplossOffset * 100.0) / 100.0);
            params.squareoff = String.valueOf(Math.round(targetOffset * 100.0) / 100.0);

            System.out.println(GREEN + "[ORDER] Sending BO: " + transactionType + " " + tradingSymbol + " qty=" + quantity + " price=" + price + " sl_offset=" + params.stoploss + " tgt_offset=" + params.squareoff + RESET);

            Order order = smartConnect.placeOrder(params, "ROBO");
            if (order != null && order.orderId != null) {
                System.out.println(GREEN + "[ORDER] Placed successfully! Order ID: " + order.orderId + RESET);
                return true;
            } else {
                System.out.println(RED + "[ORDER] Returned null or no order ID." + RESET);
                return false;
            }
        } catch (Exception e) {
            System.out.println(RED + "[ORDER] Failed for " + tradingSymbol + ": " + e.getMessage() + RESET);
            return false;
        }
    }
}
