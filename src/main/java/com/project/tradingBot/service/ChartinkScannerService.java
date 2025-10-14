package com.project.tradingBot.service;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.project.tradingBot.Config.ChartinkConfig;

import java.time.Duration;
import java.util.List;

@Service
public class ChartinkScannerService {

    @Autowired
    private ChartinkConfig chartinkConfig;
    
    

    // ----- Color Constants -----
    private static final String RESET = "\033[0m";
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";

    public void runScanner() {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        // options.addArguments("--headless=new"); // uncomment for headless execution

        WebDriver driver = new ChromeDriver(options);

        try {
            System.out.println(CYAN + "\n===================== [CHARTINK] SCANNER START =====================" + RESET);

            login(driver);

            // Run Negative Scan
            runScan(driver, chartinkConfig.getNegativeScanUrl(), true);

            // Run Positive Scan
            runScan(driver, chartinkConfig.getPositiveScanUrl(), false);

            System.out.println(CYAN + "===================== [CHARTINK] SCANNER END =====================\n" + RESET);

        } catch (Exception e) {
            System.out.println(RED + "[ERROR] Could not fetch scan data today: " + e.getMessage() + RESET);
        } finally {
            driver.quit();
        }
    }

    private void login(WebDriver driver) {
        try {
            System.out.println(CYAN + "[LOGIN] Navigating to login page..." + RESET);
            driver.get(chartinkConfig.getLoginUrl());

            driver.findElement(By.id("login-email")).sendKeys(chartinkConfig.getUsername());
            driver.findElement(By.id("login-password")).sendKeys(chartinkConfig.getPassword());

            WebElement loginButton = driver.findElement(By.cssSelector("button.primary-button"));
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.elementToBeClickable(loginButton));

            loginButton.click();

            new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(ExpectedConditions.urlContains("chartink.com"));

            System.out.println(GREEN + "[LOGIN] Successfully logged in!" + RESET);

        } catch (Exception e) {
            System.out.println(RED + "[LOGIN] Failed to login: " + e.getMessage() + RESET);
        }
    }

    private void runScan(WebDriver driver, String scanUrl, boolean isNegative) {
        String scanType = isNegative ? "Negative" : "Positive";
        try {
            System.out.println(CYAN + "\n[SCAN] Starting " + scanType + " scan..." + RESET);
            driver.get(scanUrl);

            WebElement scanButton = new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(ExpectedConditions.elementToBeClickable(By.cssSelector("div[title='Click to run scan']")));
            scanButton.click();

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
            WebElement table = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("table[class*='rounded-b']")));

            List<WebElement> rows = table.findElements(By.cssSelector("tbody tr"));

            if (rows.isEmpty()) {
                System.out.println(YELLOW + "[SCAN] " + scanType + " scan: No stock data available." + RESET);
            } else {
                System.out.println(GREEN + "[SCAN] " + scanType + " scan results:" + RESET);
                for (WebElement row : rows) {
                    List<WebElement> cells = row.findElements(By.tagName("td"));
                    if (cells.size() >= 7) {
                        String stockName = cells.get(1).getText().trim();
                        String symbol = cells.get(2).getText().trim();
                        String percentChg = cells.get(4).getText().trim();
                        String price = cells.get(5).getText().trim();
                        String volume = cells.get(6).getText().trim();

                        System.out.printf(CYAN + "→ %-15s | %-10s | %%Chg: %-7s | Price: %-7s | Volume: %s%n" + RESET,
                                stockName, symbol, percentChg, price, volume);

                        if (isNegative) {
                            PopulateScanResultService.populateNegativeScannedStocks(stockName, symbol);
                        } else {
                            PopulateScanResultService.populatePositiveScannedStocks(stockName, symbol);
                        }
                    }
                }
            }

            System.out.println(CYAN + "[SCAN] Completed " + scanType + " scan." + RESET);

        } catch (Exception e) {
            System.out.println(RED + "[SCAN] Failed to run " + scanType + " scan: " + e.getMessage() + RESET);
        }
    }
}
