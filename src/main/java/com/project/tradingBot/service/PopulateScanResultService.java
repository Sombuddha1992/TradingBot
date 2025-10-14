package com.project.tradingBot.service;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class PopulateScanResultService {

    // --- File paths ---
    private static final String MASTER_FILE = "scrip_master.csv";   // full file from AngelOne
    private static final String EQUITIES_FILE = "equities.csv";     // filtered equities only
    private static final String MASTER_URL =
            "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json";

    // --- Maps ---
    public static Map<String, String> masterEquitiesMap = new HashMap<>();   // all NSE equities
    
    public static final Map<String, String> positiveStocksScannedMap = new HashMap<>(); //day's scanned positive stocks
    public static final Map<String, String> negativeStocksScannedMap = new HashMap<>(); //day's scanned negative stocks
    
    public static final Map<String, String> selectedStocksScannedMap = new HashMap<>(); // days scanned map based on nifty

    /**
     * Step 1: Ensure equities.csv exists (download + filter if needed)
     * Step 2: Load equities into masterEquitiesMap
     */
    public static void initialize() throws IOException {
        ensureEquitiesFileExists();
        loadMasterEquitiesMap();
    }

    // -------------------- MASTER MAP --------------------

    public static Map<String, String> getMasterEquitiesMap() {
        return masterEquitiesMap;
    }

    public static void printAllMasterEquities() {
        if (!masterEquitiesMap.isEmpty()) {
            System.out.println("\n[MASTER] NSE Cash Equities (Symbol → Token):\n");
            masterEquitiesMap.forEach((symbol, token) ->
                    System.out.println("Symbol: " + symbol + ", Token: " + token));
        } else {
            System.out.println("[MASTER] No equities data loaded.");
        }
    }

    // -------------------- SCANNED MAP --------------------

    public static void populatePositiveScannedStocks(String stockName, String symbol) {
        positiveStocksScannedMap.put(stockName, symbol);
    }
    
    public static void populateNegativeScannedStocks(String stockName, String symbol) {
        negativeStocksScannedMap.put(stockName, symbol);
    }
    
    public void populateSelectedScannedStocks(String stockName, String symbol) {
        selectedStocksScannedMap.put(stockName, symbol);
    }

    public static Map<String, String> getPositiveScannedStocksMap() {
        return positiveStocksScannedMap;
    }
    
    public static Map<String, String> getNegativeScannedStocksMap() {
        return negativeStocksScannedMap;
    }
    
    public Map<String, String> getSelectedScannedStocksMap() {
        return selectedStocksScannedMap;
    }

    public static void printAllScannedStocks() {
        if (!selectedStocksScannedMap.isEmpty()) {
            System.out.println("\n[SCANNED] Stock Name and Symbol Pairs:\n");
            selectedStocksScannedMap.forEach((name, symbol) ->
                    System.out.println("Stock Name: " + name + ", Symbol: " + symbol));
        } else {
            System.out.println("[SCANNED] No scanned stock data available.");
        }
    }

    // -------------------- INTERNAL HELPERS --------------------

    private static void ensureEquitiesFileExists() throws IOException {
        File eqFile = new File(EQUITIES_FILE);
        if (eqFile.exists()) {
            System.out.println("[INFO] Equities file already exists. Skipping creation.");
            return;
        }

        // Download master JSON file if missing
        File master = new File(MASTER_FILE);
        if (!master.exists()) {
            System.out.println("[INFO] Downloading AngelOne Scrip Master JSON...");
            try (InputStream in = new URL(MASTER_URL).openStream()) {
                Files.copy(in, Paths.get(MASTER_FILE));
            }
            System.out.println("[INFO] Master file downloaded: " + MASTER_FILE);
        }

        // Filter NSE equities (symbol ends with -EQ) and write to EQUITIES_FILE
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(new File(MASTER_FILE));

        ArrayNode filteredArray = mapper.createArrayNode();
        int stockCount = 0;

        for (JsonNode node : root) {
            String symbol = node.get("symbol").asText();
            String token = node.get("token").asText();
            String exchSeg = node.get("exch_seg").asText();

            if (symbol.endsWith("-EQ") && "NSE".equalsIgnoreCase(exchSeg)) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("symbol", symbol);
                obj.put("token", token);
                filteredArray.add(obj);
                stockCount++;
            }
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(EQUITIES_FILE), filteredArray);
        System.out.println("Total Stocks copied: " + stockCount);
        System.out.println("[INFO] Equities file created: " + EQUITIES_FILE + " with " + filteredArray.size() + " entries.");
    }
    
    private static void loadMasterEquitiesMap() throws IOException {
        File eqFile = new File(EQUITIES_FILE);
        if (!eqFile.exists()) {
            System.out.println("[ERROR] Equities file not found: " + EQUITIES_FILE);
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(eqFile);

        masterEquitiesMap.clear();

        for (JsonNode node : root) {
            String symbol = node.get("symbol").asText();
            String token = node.get("token").asText();

            masterEquitiesMap.put(symbol, token);
        }

        System.out.println("[INFO] Loaded " + masterEquitiesMap.size() + " equities into masterEquitiesMap.");
    }

}
