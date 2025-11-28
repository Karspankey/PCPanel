package com.getpcpanel.hid;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;

import org.hid4java.HidManager;
import org.hid4java.HidServicesListener;
import org.hid4java.event.HidServicesEvent;

import lombok.SneakyThrows;

public class HidDebug {
    private final PrintWriter writer;

    @SneakyThrows
    public HidDebug() {
        // NOTE: USERPROFILE is Windows-specific; on macOS this will be null.
        // For now keep it as-is since HidDebug isn't used in normal startup.
        var outputFile = new File(System.getenv("USERPROFILE") + "/.pcpanel/hid-debug.txt");
        outputFile.getParentFile().mkdirs();
        writer = new PrintWriter(new FileOutputStream(outputFile));
    }

    @SneakyThrows
    public void execute() {
        var hidServices = HidManager.getHidServices(DeviceScanner.buildSpecification());
        hidServices.addHidServicesListener(buildListener());
        write("Starting HID Debug");
        hidServices.start();
        write("HID Debug started");

        write("Waiting 10 seconds...");
        Thread.sleep(10_000);
        write("Waited 10 seconds, stopping");
        writer.close();
    }

    private HidServicesListener buildListener() {
        return new HidServicesListener() {
            @Override
            public void hidDeviceAttached(HidServicesEvent event) {
                write("Device attached: " + event);
            }

            @Override
            public void hidDeviceDetached(HidServicesEvent event) {
                write("Device detached: " + event);
            }

            @Override
            public void hidFailure(HidServicesEvent event) {
                write("Hid failure: " + event);
            }

            @Override
            public void hidDataReceived(HidServicesEvent event) {
                // New method required by hid4java 0.8.0
                write("Hid data received: " + event);
            }
        };
    }

    private void write(String line) {
        writer.println(line);
        writer.flush();
    }
}
