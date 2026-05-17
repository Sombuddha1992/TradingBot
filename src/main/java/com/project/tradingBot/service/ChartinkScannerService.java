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

import static com.project.tradingBot.util.ConsoleColors.*;

@Service
public class ChartinkScannerService {

    @Autowired
    private ChartinkConfig chartinkConfig;

    public void runScanner() {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();

        WebDriver driver = new ChromeDriver(options);

        try {
            System.out.println(CYAN + "===================== [CHARTINK] SCANNER START =====================" + RESET);

            login(driver);
            runScan(driver, chartinkConfig.getNegativeScanUrl(), true);
            runScan(driver, chartinkConfig.getPositiveScanUrl(), false);

            System.out.println(CYAN + "===================== [CHARTINK] SCANNER END =====================" + RESET);

        } catch (Exception e) {
            System.out.println(RED + "Could not fetch scan data today: " + e.getMessage() + RESET);
        } finally {
            driver.quit();
        }
    }

    private void login(WebDriver driver) {
        try {
            System.out.println(CYAN + "Navigating to Chartink login page..." + RESET);
            driver.get(chartinkConfig.getLoginUrl());

            driver.findElement(By.id("login-email")).sendKeys(chartinkConfig.getUsername());
            driver.findElement(By.id("login-password")).sendKeys(chartinkConfig.getPassword());

            WebElement loginButton = driver.findElement(By.cssSelector("button.primary-button"));
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.elementToBeClickable(loginButton));
            loginButton.click();

            new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(ExpectedConditions.urlContains("chartink.com"));

            System.out.println(GREEN + "Chartink login successful!" + RESET);

        } catch (Exception e) {
            System.out.println(RED + "Chartink login failed: " + e.getMessage() + RESET);
            throw new RuntimeException("Chartink login failed — scanner cannot proceed unauthenticated", e);
        }
    }

    private void runScan(WebDriver driver, String scanUrl, boolean isNegative) {
        String scanType = isNegative ? "Negative" : "Positive";

        try {
            System.out.println(CYAN + "\n[SCAN] Starting " + scanType + " scan..." + RESET);
            driver.get(scanUrl);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
            WebElement table = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("table.scan-results-table")
            ));

            List<WebElement> rows = table.findElements(By.cssSelector("tbody tr"));

            if (rows.isEmpty()) {
                System.out.println(YELLOW + scanType + " scan: No stock data available." + RESET);
                return;
            }

            System.out.println(GREEN + scanType + " scan results:" + RESET);

            for (WebElement row : rows) {
                List<WebElement> cells = row.findElements(By.tagName("td"));

                if (cells.size() >= 6) {
                    String stockName = cells.get(1).getText().trim();
                    String symbol = cells.get(2).getText().trim();
                    String close = cells.get(3).getText().trim();
                    String percentChg = cells.get(4).getText().trim();
                    String volume = cells.get(5).getText().trim();

                    System.out.println(String.format("%s→ %-30s | %-10s | Close: %-8s | %%Chg: %-7s | Volume: %s%s",
                            CYAN, stockName, symbol, close, percentChg, volume, RESET));

                    if (isNegative) {
                        PopulateScanResultService.populateNegativeScannedStocks(stockName, symbol);
                    } else {
                        PopulateScanResultService.populatePositiveScannedStocks(stockName, symbol);
                    }
                }
            }

            System.out.println(CYAN + "Completed " + scanType + " scan." + RESET);

        } catch (Exception e) {
            System.out.println(RED + "Failed to run " + scanType + " scan: " + e.getMessage() + RESET);
        }
    }
}
