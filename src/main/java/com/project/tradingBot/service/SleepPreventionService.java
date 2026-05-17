package com.project.tradingBot.service;

import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;

import static com.project.tradingBot.util.ConsoleColors.*;

/**
 * Prevents Windows from sleeping while the trading bot is running.
 * Uses Win32 SetThreadExecutionState API via PowerShell.
 * Restores the original power settings on shutdown.
 */
@Service
public class SleepPreventionService {

    private Process caffeineProcess;

    public void preventSleep() {
        try {
            String psCommand = "powershell -Command \"" +
                    "Add-Type -TypeDefinition '" +
                    "using System; using System.Runtime.InteropServices; " +
                    "public class SleepPreventer { " +
                    "[DllImport(\\\"kernel32.dll\\\")] " +
                    "public static extern uint SetThreadExecutionState(uint esFlags); " +
                    "public const uint ES_CONTINUOUS = 0x80000000; " +
                    "public const uint ES_SYSTEM_REQUIRED = 0x00000001; " +
                    "public const uint ES_DISPLAY_REQUIRED = 0x00000002; " +
                    "}'; " +
                    "[SleepPreventer]::SetThreadExecutionState(" +
                    "[SleepPreventer]::ES_CONTINUOUS -bor " +
                    "[SleepPreventer]::ES_SYSTEM_REQUIRED -bor " +
                    "[SleepPreventer]::ES_DISPLAY_REQUIRED); " +
                    "Write-Host 'Sleep prevention active'; " +
                    "while($true) { Start-Sleep -Seconds 60; " +
                    "[SleepPreventer]::SetThreadExecutionState(" +
                    "[SleepPreventer]::ES_CONTINUOUS -bor " +
                    "[SleepPreventer]::ES_SYSTEM_REQUIRED -bor " +
                    "[SleepPreventer]::ES_DISPLAY_REQUIRED); }\"";

            caffeineProcess = Runtime.getRuntime().exec(psCommand);
            System.out.println(GREEN + "Windows sleep prevention ACTIVE — machine will not sleep." + RESET);
        } catch (IOException e) {
            System.out.println(YELLOW + "Could not prevent sleep (non-Windows OS?): " + e.getMessage() + RESET);
        }
    }

    @PreDestroy
    public void allowSleep() {
        if (caffeineProcess != null && caffeineProcess.isAlive()) {
            caffeineProcess.destroyForcibly();
            System.out.println(YELLOW + "Sleep prevention DISABLED — machine can sleep again." + RESET);
        }
        try {
            Process resetProcess = Runtime.getRuntime().exec("powershell -Command \"" +
                    "Add-Type -TypeDefinition '" +
                    "using System; using System.Runtime.InteropServices; " +
                    "public class SleepReset { " +
                    "[DllImport(\\\"kernel32.dll\\\")] " +
                    "public static extern uint SetThreadExecutionState(uint esFlags); " +
                    "public const uint ES_CONTINUOUS = 0x80000000; " +
                    "}'; " +
                    "[SleepReset]::SetThreadExecutionState([SleepReset]::ES_CONTINUOUS);\"");
            resetProcess.waitFor();
            resetProcess.destroy();
        } catch (IOException | InterruptedException e) {
            System.out.println("Failed to reset sleep state: " + e.getMessage());
        }
    }
}
