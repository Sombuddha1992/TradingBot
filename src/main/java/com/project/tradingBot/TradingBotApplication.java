package com.project.tradingBot;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.project.tradingBot.service.ChartinkScannerService;
import com.project.tradingBot.service.PopulateScanResultService;
import com.project.tradingBot.service.SmartApiService;
import com.project.tradingBot.service.StrategyEngine;

@SpringBootApplication
public class TradingBotApplication implements CommandLineRunner{

	private static final Logger logger = LoggerFactory.getLogger(TradingBotApplication.class);
	
	@Autowired
	private ChartinkScannerService scannerService;
	@Autowired
	SmartApiService smartApiService;
	@Autowired
	private StrategyEngine strategy;
	
	
	public static void main(String[] args) {
		
		SpringApplication.run(TradingBotApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		 
        // Wait until 9:36 AM
	    LocalTime targetTime = LocalTime.of(9, 36, 0);
	    LocalTime now = LocalTime.now();

	    long sleepSeconds = Duration.between(now, targetTime).getSeconds();
	    if (sleepSeconds > 0) {
	        logger.info("Waiting for 9:36 AM. Sleeping for " + sleepSeconds + " seconds.");
	        Thread.sleep(sleepSeconds * 1000); // wait until target time
	    } else {
	    	logger.info("It's already past 9:36 AM. Executing immediately.");
	    }
	    
	    logger.info("Starting bot at " + LocalTime.now());
	    
	    // Step 1: Login to SmartAPI
        smartApiService.login();
		
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
			e.printStackTrace();
		}
		
		// Step 6: Actual Trading Starts
		strategy.start();
	}
	
}
