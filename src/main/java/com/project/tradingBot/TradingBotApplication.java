package com.project.tradingBot;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.project.tradingBot.service.ChartinkScannerService;
import com.project.tradingBot.service.PopulateScanResultService;
import com.project.tradingBot.service.SleepPreventionService;
import com.project.tradingBot.service.SmartApiService;
import com.project.tradingBot.service.OrbStrategyEngine;
import com.project.tradingBot.service.VwapStrategyEngine;

@SpringBootApplication
public class TradingBotApplication implements CommandLineRunner{

	@Autowired
	private ChartinkScannerService scannerService;
	@Autowired
	private SmartApiService smartApiService;
	@Autowired
	private OrbStrategyEngine strategy;
	@Autowired
	private VwapStrategyEngine vwapStrategy;
	@Autowired
	private SleepPreventionService sleepPrevention;
	
	
	public static void main(String[] args) {
		
		SpringApplication.run(TradingBotApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		 
	    System.out.println("Starting bot at " + LocalTime.now());
	    
	    // Step 0: Prevent machine from sleeping
	    sleepPrevention.preventSleep();
	    
	    // Step 1: Login to SmartAPI
	    try {
	        smartApiService.login();
	    } catch (Exception e) {
	        System.out.println("SmartAPI login failed. Cannot proceed: " + e.getMessage());
	        return;
	    }
		
		 // Step 2: Run the scanner in chartink to get the stocks for the day
	    scannerService.runScanner();

	    // Step 3: Ensure equities file exists in the classpath & load master map to it if not done to get token for each stock symbol
	    PopulateScanResultService.initialize();

	    // Step 4: Print all stocks in key-value pair format
	    PopulateScanResultService.printAllScannedStocks();
        
        Map<String, String> positiveStockMap = PopulateScanResultService.getPositiveScannedStocksMap();
        Map<String, String> negativeStockMap = PopulateScanResultService.getNegativeScannedStocksMap();
        
		List<String> pos = new ArrayList<>(positiveStockMap.values()); 
	    List<String> neg = new ArrayList<>(negativeStockMap.values());
		
		try {
		// Step 5: Do prerequisites for trading
			strategy.init(pos, neg);
		} catch (Exception e) {
			System.out.println("Strategy initialization failed");
			e.printStackTrace();
		}
		
		// Step 6: Actual Trading Starts (ORB: 9:30-12:00)
		strategy.start();
		
		// Step 7: Initialize VWAP strategy (runs 12:00-15:00, time-gated by its own delay)
		try {
			vwapStrategy.init();
		} catch (Exception e) {
			System.out.println("VWAP strategy initialization failed");
			e.printStackTrace();
		}
		
		// Step 8: Start VWAP strategy (will auto-delay until 12:00 PM)
		vwapStrategy.start();
	}
	
}
