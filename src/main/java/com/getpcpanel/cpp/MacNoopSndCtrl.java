package com.getpcpanel.cpp;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * macOS placeholder implementation of ISndCtrl.
 * This lets PCPanel start on macOS even though the Windows SndCtrl.dll
 * backend is not available. All methods are currently no-ops.
 */
@Service
@ConditionalOnMissingBean(ISndCtrl.class)
public class MacNoopSndCtrl implements ISndCtrl {
    private static final Logger log = LoggerFactory.getLogger(MacNoopSndCtrl.class);

    public MacNoopSndCtrl() {
        log.warn("Using MacNoopSndCtrl: audio/session control is not implemented on macOS yet.");
    }

    @Override
    public Map<String, AudioDevice> getDevicesMap() {
        log.debug("getDevicesMap() called on MacNoopSndCtrl");
        return Collections.emptyMap();
    }

    @Override
    public Collection<AudioDevice> getDevices() {
        log.debug("getDevices() called on MacNoopSndCtrl");
        return Collections.emptyList();
    }

    @Override
    public Collection<AudioSession> getAllSessions() {
        log.debug("getAllSessions() called on MacNoopSndCtrl");
        return Collections.emptyList();
    }

    @Override
    public AudioDevice getDevice(String id) {
        log.debug("getDevice({}) called on MacNoopSndCtrl", id);
        return null;
    }

    @Override
    public void setDeviceVolume(String deviceId, float volume) {
        log.warn("setDeviceVolume({}, {}) called on MacNoopSndCtrl – not implemented", deviceId, volume);
    }

    @Override
    public void muteDevice(String deviceId, MuteType mute) {
        log.warn("muteDevice({}, {}) called on MacNoopSndCtrl – not implemented", deviceId, mute);
    }

    @Override
    public void setDefaultDevice(String deviceId) {
        log.warn("setDefaultDevice({}) called on MacNoopSndCtrl – not implemented", deviceId);
    }

    @Override
    public void setProcessVolume(String fileName, String device, float volume) {
        log.warn("setProcessVolume({}, {}, {}) called on MacNoopSndCtrl – not implemented", fileName, device, volume);
    }

    @Override
    public void setFocusVolume(float volume) {
        log.warn("setFocusVolume({}) called on MacNoopSndCtrl – not implemented", volume);
    }

    @Override
    public void muteProcesses(Set<String> fileName, MuteType mute) {
        log.warn("muteProcesses({}, {}) called on MacNoopSndCtrl – not implemented", fileName, mute);
    }

    @Override
    public String getFocusApplication() {
        log.debug("getFocusApplication() called on MacNoopSndCtrl");
        return null;
    }

    @Override
    public List<RunningApplication> getRunningApplications() {
        log.debug("getRunningApplications() called on MacNoopSndCtrl");
        return Collections.emptyList();
    }

    @Override
    public String defaultDeviceOnEmpty(String deviceId) {
        log.debug("defaultDeviceOnEmpty({}) called on MacNoopSndCtrl", deviceId);
        return deviceId; // just return input unchanged
    }

    @Override
    public String defaultPlayer() {
        log.debug("defaultPlayer() called on MacNoopSndCtrl");
        return null;
    }

    @Override
    public String defaultRecorder() {
        log.debug("defaultRecorder() called on MacNoopSndCtrl");
        return null;
    }
}
