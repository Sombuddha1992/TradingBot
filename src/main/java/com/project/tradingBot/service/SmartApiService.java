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

@Service
public class SmartApiService {

    @Autowired
    private SmartApiConfig cfg;
    @Autowired
    private TotpUtilService totpUtilService;
    
    private SmartConnect smartConnect;
    
    // ---------------------- ANSI COLORS ----------------------
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    

 // --- Login ---
    public void login() {
        try {
             // Close old connection if any
             if (smartConnect != null) {
            	 System.out.println(YELLOW + "[AUTH] Reauthenticating SmartAPI session..." + RESET);
                 try {
                     smartConnect = null;
                     Thread.sleep(500); // small delay to ensure old connection is cleared
                 } catch (Exception ignored) {}
             }
             
            System.out.println("[LOGIN] Logging in to SmartAPI...");

            smartConnect = new SmartConnect();
            smartConnect.setApiKey(cfg.getTradingApiKey());
            smartConnect.setSessionExpiryHook(() -> System.out.println("[SMARTAPI] Session expired"));

            String otp = totpUtilService.generateTotp(cfg.getTradingTotpSecret());
            User user = smartConnect.generateSession(cfg.getTradingClientId(), cfg.getTradingPassword(), otp);

            smartConnect.setAccessToken(user.getAccessToken());
            smartConnect.setUserId(user.getUserId());

            System.out.println("[LOGIN] SmartAPI login successful. User ID: " + user.getUserId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // --- Balance ---
    public double getBalance() {
        try {
            //JSONObject rms = tradingConnect.getRMS();
        	JSONObject rms = smartConnect.getRMS();
            return rms.optDouble("net", 0.0);
        } catch (Exception e) {
            e.printStackTrace();
            return 0.0;
        }
    }

    // --- Nifty Change % ---
    public double getNiftyChangePercent() {
        try {
            // Symbol token for NIFTY 50 Index = 99926000 (AngelOne convention)
            JSONObject ltp = smartConnect.getLTP("NSE", "NIFTY 50", "99926000");

            double close = ltp.optDouble("close", 0.0);       // yesterday's close
            double lastPrice = ltp.optDouble("ltp", 0.0);     // current price

            if (close == 0.0) return 0.0;
            return ((lastPrice - close) / close) * 100;
        } catch (Exception e) {
            e.printStackTrace();
            return 0.0;
        }
    }

 // --- Candles ---
    public synchronized List<Candle> getHistoricalCandles(String symbol, String interval, String fromDate, String toDate) {
        List<Candle> candles = new ArrayList<>();

        try {
            // Append -EQ to match the keys in masterEquitiesMap
            String symbolWithEQ = symbol + "-EQ";

            // Fetch token from the map
            String token = PopulateScanResultService.masterEquitiesMap.get(symbolWithEQ);
            if (token == null) {
                System.out.println("[WARN] Token not found for symbol: " + symbolWithEQ);
                return candles;
            }

            JSONObject payload = new JSONObject();
            payload.put("exchange", "NSE");
            payload.put("symboltoken", token);
            payload.put("interval", interval);
            payload.put("fromdate", fromDate);
            payload.put("todate", toDate);

            JSONArray data = smartConnect.candleData(payload);

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

            // Add a small delay to avoid throttling
            Thread.sleep(2000); // 2 second delay

        } catch (Exception e) {
            e.printStackTrace();
        }

        return candles;
    }


    // --- Bracket Order ---
    public boolean placeBracketOrder(String tradingSymbol, String transactionType,
                                     int quantity, double price, double stopLoss, double target) {
        try {
        	 // Append -EQ to match the keys in masterEquitiesMap
            String symbolWithEQ = tradingSymbol + "-EQ";

            // Fetch token from the map
            String token = PopulateScanResultService.masterEquitiesMap.get(symbolWithEQ);
            
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
            params.stoploss = String.valueOf(stopLoss);
            params.squareoff = String.valueOf(target);

            //Order order = tradingConnect.placeOrder(params, "ROBO");
            Order order = smartConnect.placeOrder(params, "ROBO");
            return order != null && order.orderId != null;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
