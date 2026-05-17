package com.project.tradingBot.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmartApiConfig {

    @Value("${smartapi.apiKey}")
    private String apiKey;

    @Value("${smartapi.clientId}")
    private String clientId;

    @Value("${smartapi.password}")
    private String password;

    @Value("${smartapi.totpSecret}")
    private String totpSecret;

    public String getApiKey() { return apiKey; }
    public String getClientId() { return clientId; }
    public String getPassword() { return password; }
    public String getTotpSecret() { return totpSecret; }
}
