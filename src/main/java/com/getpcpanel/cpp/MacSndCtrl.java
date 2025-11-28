package com.getpcpanel.cpp;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
public class MacSndCtrl implements ISndCtrl {
    private static final Map<String, AudioDevice> EMPTY_DEVICE_MAP = Collections.emptyMap();
    private static final Collection<AudioDevice> EMPTY_DEVICES = Collections.emptyList();
    private static final Collection<AudioSession> EMPTY_SESSIONS = Collections.emptyList();
    private static final List<RunningApplication> EMPTY_APPS = Collections.emptyList();

    // Cached list of running applications at startup
    private volatile List<RunningApplication> runningApplications = EMPTY_APPS;

    public MacSndCtrl() {
        log.info("MacSndCtrl initialized on macOS – testing running application detection");
        this.runningApplications = loadRunningApplicationsFromAppleScript();
        log.info("MacSndCtrl initial running apps: {}", this.runningApplications);
    }

    // ---------- ISndCtrl: devices ----------

    @Override
    public Map<String, AudioDevice> getDevicesMap() {
        // No CoreAudio binding yet, so return empty.
        return EMPTY_DEVICE_MAP;
    }

    @Override
    public Collection<AudioDevice> getDevices() {
        return EMPTY_DEVICES;
    }

    @Override
    public Collection<AudioSession> getAllSessions() {
        return EMPTY_SESSIONS;
    }

    @Override
    public AudioDevice getDevice(String id) {
        return null;
    }

    @Override
    public void setDeviceVolume(String deviceId, float volume) {
        // TODO: implement via CoreAudio or a native helper if you ever want real per-device volume
        log.debug("setDeviceVolume({}, {}) – not implemented on macOS yet", deviceId, volume);
    }

    @Override
    public void muteDevice(String deviceId, MuteType mute) {
        log.debug("muteDevice({}, {}) – not implemented on macOS yet", deviceId, mute);
    }

    @Override
    public void setDefaultDevice(String deviceId) {
        log.debug("setDefaultDevice({}) – not implemented on macOS yet", deviceId);
    }

    // ---------- ISndCtrl: per-process / focus ----------

    @Override
    public void setProcessVolume(String fileName, String device, float volume) {
        log.debug("setProcessVolume({}, {}, {}) – not implemented on macOS yet", fileName, device, volume);
    }

    @Override
    public void setFocusVolume(float volume) {
        log.debug("setFocusVolume({}) – not implemented on macOS yet", volume);
    }

    @Override
    public void muteProcesses(Set<String> fileNames, MuteType mute) {
        log.debug("muteProcesses({}, {}) – not implemented on macOS yet", fileNames, mute);
    }

    @Override
    public String getFocusApplication() {
        try {
            // AppleScript: get bundle identifier of frontmost app
            String script =
                "tell application \"System Events\" to get bundle identifier of first process whose frontmost is true";
            String[] cmd = { "osascript", "-e", script };
            Process p = new ProcessBuilder(cmd).start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    String bundleId = line.trim();
                    log.debug("Frontmost application (macOS): {}", bundleId);
                    return bundleId;
                }
            }

            int exitCode = p.waitFor();
            if (exitCode != 0) {
                log.warn("getFocusApplication AppleScript exited with code {}", exitCode);
            }
        } catch (Exception e) {
            log.error("Error determining focus application on macOS", e);
        }

        return null;
    }

    @Override
    public List<RunningApplication> getRunningApplications() {
        // Just return the cached snapshot for now
        return List.copyOf(runningApplications);
    }

    @Override
    public String defaultDeviceOnEmpty(String deviceId) {
        return deviceId;
    }

    @Override
    public String defaultPlayer() {
        return null;
    }

    @Override
    public String defaultRecorder() {
        return null;
    }

    // ---------- helpers ----------

    /**
     * Use AppleScript to fetch three parallel lists:
     *  - PIDs
     *  - bundle identifiers
     *  - display names
     *
     * We then split the single comma-separated line into tokens and
     * reassemble RunningApplication(pid, file, name).
     */
    private List<RunningApplication> loadRunningApplicationsFromAppleScript() {
        var apps = new ArrayList<RunningApplication>();

        try {
            String script = """
                set pidList to ""
                set bundleList to ""
                set nameList to ""
                tell application "System Events"
                    set theProcs to every process whose background only is false
                    repeat with p in theProcs
                        set pidList to pidList & (unix id of p as string) & ", "
                        try
                            set bundleList to bundleList & (bundle identifier of p as string) & ", "
                        on error
                            set bundleList to bundleList & "missing value, "
                        end try
                        set nameList to nameList & (name of p as string) & ", "
                    end repeat
                end tell
                -- strip trailing ", " from each list
                if (length of pidList) > 2 then set pidList to text 1 thru -3 of pidList
                if (length of bundleList) > 2 then set bundleList to text 1 thru -3 of bundleList
                if (length of nameList) > 2 then set nameList to text 1 thru -3 of nameList
                return pidList & ", " & bundleList & ", " & nameList
                """;

            Process proc = new ProcessBuilder("osascript", "-e", script).start();

            String result;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                result = reader.readLine();
            }

            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                log.warn("AppleScript for running apps exited with code {}", exitCode);
            }

            if (result == null || result.isBlank()) {
                log.warn("AppleScript returned empty result for running apps");
                return EMPTY_APPS;
            }

            String[] tokens = result.split("\\s*,\\s*");
            if (tokens.length < 3 || tokens.length % 3 != 0) {
                log.warn("Unexpected format from AppleScript for running apps ({} tokens): {}",
                        tokens.length, result);
                return EMPTY_APPS;
            }

            int n = tokens.length / 3;
            for (int i = 0; i < n; i++) {
                String pidStr = tokens[i];
                String bundle = tokens[i + n];
                String name = tokens[i + 2 * n];

                int pid;
                try {
                    pid = Integer.parseInt(pidStr);
                } catch (NumberFormatException e) {
                    log.debug("Skipping non-numeric pid '{}' for app '{}'", pidStr, name);
                    continue;
                }

                // We don't have a real executable path here, so we stuff the bundle id into the "file" field
                // as a placeholder. The 'name' field is the user-visible name.
                File file = new File(bundle != null ? bundle : name);

                apps.add(new RunningApplication(pid, file, name));
            }

            log.info("Parsed running applications from AppleScript: {}", apps);
            return apps;
        } catch (Exception e) {
            log.error("Error querying running apps via AppleScript", e);
            return EMPTY_APPS;
        }
    }

    @SuppressWarnings("unused")
    private static String stripQuotes(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
