package com.getpcpanel.hid;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.hid4java.HidServicesListener;
import org.hid4java.HidServicesSpecification;
import org.hid4java.ScanMode;
import org.hid4java.event.HidServicesEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.getpcpanel.device.DeviceType;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
@RequiredArgsConstructor
public class DeviceScanner implements HidServicesListener {
    private final ConcurrentHashMap<String, DeviceCommunicationHandler> connectedDeviceMap = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventPublisher;
    @Autowired @Lazy @Setter private DeviceCommunicationHandlerFactory deviceCommunicationHandlerFactory;

    private HidServices hidServices;

    public DeviceCommunicationHandler getConnectedDevice(String key) {
        return connectedDeviceMap.get(key);
    }

    // Not @PostConstruct because the HomePage must have loaded before
    public void init() {
        // On Apple Silicon macOS we used to skip HID because the native lib was x86_64-only.
        // Now that we have a darwin-aarch64 hidapi in the JAR, we don't skip anymore.

        try {
            HidServicesSpecification spec = buildSpecification();
            hidServices = HidManager.getHidServices(spec);

            // Register this scanner as listener so attach/detach/failure events fire
            hidServices.addHidServicesListener(this);

            log.info("HID: Listing attached devices...");
            for (HidDevice d : hidServices.getAttachedHidDevices()) {
                log.info("HID device: vendorId=0x{}, productId=0x{}, path={}, manufacturer={}, product={}",
                         Integer.toHexString(d.getVendorId()),
                         Integer.toHexString(d.getProductId()),
                         d.getPath(),
                         d.getManufacturer(),
                         d.getProduct());

                // If it's a PCPanel, register it immediately
                determineType(d).ifPresent(type -> foundPCPanel(d, type));
            }

            // Start background scanning
            hidServices.start();
        } catch (org.hid4java.HidException | UnsatisfiedLinkError e) {
            log.error("Failed to initialize HID services, running without device scanning", e);
        }
    }

    static HidServicesSpecification buildSpecification() {
        var hidServicesSpecification = new HidServicesSpecification();
        hidServicesSpecification.setAutoShutdown(false);
        hidServicesSpecification.setAutoStart(false);
        hidServicesSpecification.setScanInterval(3000);
        hidServicesSpecification.setPauseInterval(2000);
        hidServicesSpecification.setScanMode(ScanMode.SCAN_AT_FIXED_INTERVAL);
        return hidServicesSpecification;
    }

    public void deviceAdded(@NonNull String key, @NonNull HidDevice device, DeviceType deviceType) {
        if (!device.isOpen()) {
            if (!device.open()) {
                log.error("Unable to open device {} (vid=0x{}, pid=0x{}), lastError={}",
                          key,
                          Integer.toHexString(device.getVendorId()),
                          Integer.toHexString(device.getProductId()),
                          device.getLastErrorMessage());
                // Do NOT continue with a closed device – no reader/writer threads
                return;
            }
        }

        var deviceHandler = deviceCommunicationHandlerFactory.build(key, device, deviceType);
        connectedDeviceMap.put(key, deviceHandler);
        deviceHandler.start();
        eventPublisher.publishEvent(new DeviceConnectedEvent(key, deviceType));
    }

    public void deviceRemoved(String key, HidDevice device) {
        if (key == null || device == null)
            throw new IllegalArgumentException("serialNum or device cannot be null serialNum: " + key + " device: " + device);
        if (connectedDeviceMap.remove(key) != null)
            eventPublisher.publishEvent(new DeviceDisconnectedEvent(key));
    }

    private void foundPCPanel(HidDevice newPCPanel, DeviceType deviceType) {
        String serial = newPCPanel.getSerialNumber();
        if (connectedDeviceMap.containsKey(serial)) {
            log.info("PCPanel {} already connected, ignoring duplicate FOUND event", serial);
            return;
        }

        log.info("FOUND PCPANEL : {}", newPCPanel);
        try {
            deviceAdded(serial, newPCPanel, deviceType);
        } catch (Exception e) {
            log.error("Unable to handle device added", e);
        }
    }


    private void lostPCPanel(HidDevice lostPCPanel) {
        log.info("LOST PCPANEL : {}", lostPCPanel);
        try {
            deviceRemoved(lostPCPanel.getSerialNumber(), lostPCPanel);
        } catch (Exception e) {
            log.error("Unable to handle device disconnect", e);
        }
    }

    // ---- HidServicesListener implementation ----

    @Override
    public void hidDeviceAttached(HidServicesEvent event) {
        determineType(event).ifPresent(type -> foundPCPanel(event.getHidDevice(), type));
    }

    @Override
    public void hidDeviceDetached(HidServicesEvent event) {
        determineType(event).ifPresent(type -> lostPCPanel(event.getHidDevice()));
    }

    @Override
    public void hidFailure(HidServicesEvent event) {
        determineType(event).ifPresent(type -> lostPCPanel(event.getHidDevice()));
    }

    @Override
    public void hidDataReceived(HidServicesEvent event) {
        // Optional debug
        log.debug("HID data received from {}", event.getHidDevice());
    }

    // ---- Helpers ----

    /** Determine device type from a HidDevice */
    private Optional<DeviceType> determineType(HidDevice device) {
        for (var deviceType : DeviceType.ALL) {
            if (device.isVidPidSerial(deviceType.getVid(), deviceType.getPid(), null)) {
                return Optional.of(deviceType);
            }
        }
        return Optional.empty();
    }

    /** Delegate from event → device */
    private Optional<DeviceType> determineType(HidServicesEvent event) {
        return determineType(event.getHidDevice());
    }

    public void close() {
        try {
            if (hidServices != null) {
                hidServices.shutdown();
            }
        } catch (Exception e) {
            log.error("Error occurred when closing device", e);
        }
    }

    public record DeviceConnectedEvent(String serialNum, DeviceType deviceType) {
    }

    public record DeviceDisconnectedEvent(String serialNum) {
    }
}
