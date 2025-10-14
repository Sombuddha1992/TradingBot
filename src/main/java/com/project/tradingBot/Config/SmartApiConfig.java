package com.project.tradingBot.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmartApiConfig {

    // Trading App
    @Value("${smartapi.trading.apiKey}")
    private String tradingApiKey;

    @Value("${smartapi.trading.clientId}")
    private String tradingClientId;

    @Value("${smartapi.trading.password}")
    private String tradingPassword;

    @Value("${smartapi.trading.totpSecret}")
    private String tradingTotpSecret;

    // Historical Data App
    @Value("${smartapi.historical.apiKey}")
    private String historicalApiKey;

    @Value("${smartapi.historical.clientId}")
    private String historicalClientId;

    @Value("${smartapi.historical.password}")
    private String historicalPassword;

    @Value("${smartapi.historical.totpSecret}")
    private String historicalTotpSecret;

    // --- Getters ---
    public String getTradingApiKey() { return tradingApiKey; }
    public String getTradingClientId() { return tradingClientId; }
    public String getTradingPassword() { return tradingPassword; }
    public String getTradingTotpSecret() { return tradingTotpSecret; }

    public String getHistoricalApiKey() { return historicalApiKey; }
    public String getHistoricalClientId() { return historicalClientId; }
    public String getHistoricalPassword() { return historicalPassword; }
    public String getHistoricalTotpSecret() { return tradingTotpSecret; }
}
