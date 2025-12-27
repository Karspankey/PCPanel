package com.getpcpanel.sleepdetection;

import java.util.concurrent.TimeUnit;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.getpcpanel.device.Device;
import com.getpcpanel.hid.DeviceHolder;
import com.getpcpanel.hid.DeviceScanner;
import com.getpcpanel.hid.OutputInterpreter;
import com.getpcpanel.profile.LightingConfig;

import jakarta.annotation.PostConstruct;
import javafx.application.Platform;
import javafx.scene.paint.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
@RequiredArgsConstructor
public final class SleepDetector {
    private static final LightingConfig ALL_OFF = LightingConfig.createAllColor(Color.BLACK);
    private static final long RESUME_GAP_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long RESUME_CHECK_DELAY_MS = 5_000L;
    private final DeviceScanner deviceScanner;
    private final OutputInterpreter outputInterpreter;
    private final DeviceHolder devices;
    private volatile long lastResumeCheckNanos = System.nanoTime();

    @PostConstruct
    public void init() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> onSuspended(true), "Shutdown SleepDetector Hook Thread"));
    }

    @EventListener
    public void onEvent(WindowsSystemEventService.WindowsSystemEvent event) {
        switch (event.type()) {
            case goingToSuspend, locked -> onSuspended(false);
            case resumedFromSuspend, unlocked -> onResumed();
        }
    }

    @Scheduled(fixedDelay = RESUME_CHECK_DELAY_MS)
    public void detectResumeGap() {
        long now = System.nanoTime();
        long delta = now - lastResumeCheckNanos;
        lastResumeCheckNanos = now;
        if (delta > RESUME_GAP_NANOS) {
            log.debug("Detected sleep gap ({} ms); resending lighting", TimeUnit.NANOSECONDS.toMillis(delta));
            onResumed();
        }
    }

    private void onSuspended(boolean shutdown) {
        Runnable r = () -> {
            for (var device : devices.values()) {
                log.debug("Pause: {}", device.getSerialNumber());
                outputInterpreter.sendLightingConfig(device.getSerialNumber(), device.getDeviceType(), ALL_OFF, true);

                if (shutdown) {
                    waitUntilEmptyPrioQueue(device);
                }
            }
            if (shutdown)
                deviceScanner.close();
        };

        if (shutdown) {
            r.run();
        } else {
            Platform.runLater(r);
        }
        log.info("Stopped sleep detector");
    }

    private void waitUntilEmptyPrioQueue(Device device) {
        var handler = deviceScanner.getConnectedDevice(device.getSerialNumber());
        for (var i = 0; i < 20; i++) {
            if (handler.getQueue().isEmpty())
                break;
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                log.warn("Unable to sleep", e);
            }
        }
    }

    private void onResumed() {
        Platform.runLater(() -> {
            for (var device : devices.values()) {
                log.info("RESUME: {}", device.getSerialNumber());
                outputInterpreter.sendLightingConfig(device.getSerialNumber(), device.getDeviceType(), device.getLightingConfig(), true);
            }
        });
    }
}
