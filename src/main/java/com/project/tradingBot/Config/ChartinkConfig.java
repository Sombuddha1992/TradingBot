package com.project.tradingBot.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chartink")
public class ChartinkConfig {

    private String loginUrl;
    private String positiveScanUrl;
    private String negativeScanUrl;
    private String username;
    private String password;

    public String getLoginUrl() {
        return loginUrl;
    }

    public void setLoginUrl(String loginUrl) {
        this.loginUrl = loginUrl;
    }
    
    public String getPositiveScanUrl() {
        return positiveScanUrl;
    }

    public void setPositiveScanUrl(String positiveScanUrl) {
        this.positiveScanUrl = positiveScanUrl;
    }

    public String getNegativeScanUrl() {
        return negativeScanUrl;
    }

    public void setNegativeScanUrl(String negativeScanUrl) {
        this.negativeScanUrl = negativeScanUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
