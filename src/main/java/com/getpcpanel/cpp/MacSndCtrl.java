package com.getpcpanel.cpp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.getpcpanel.spring.ConditionalOnMac;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
@ConditionalOnMac
public class MacSndCtrl implements ISndCtrl {
    private static final Map<String, AudioDevice> EMPTY_DEVICE_MAP = Collections.emptyMap();
    private static final Collection<AudioDevice> EMPTY_DEVICES = Collections.emptyList();
    private static final Collection<AudioSession> EMPTY_SESSIONS = Collections.emptyList();
    private static final List<RunningApplication> EMPTY_APPS = Collections.emptyList();
    private static final Pattern APP_PATH = Pattern.compile("(/.*?\\.app/Contents/MacOS/[^\\s]+)");
    private static final Pattern LSAPP_PID = Pattern.compile("pid:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LSAPP_NAME = Pattern.compile("name:\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern LSAPP_BUNDLE = Pattern.compile("bundleID:\"?([^\"]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern LSAPP_EXEC = Pattern.compile("executable\\s+path:\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cache bundle-id -> app path so we do not shell out repeatedly. */
    private final Map<String, File> bundleIdPathCache = new ConcurrentHashMap<>();
    private volatile int lastSystemVolume = -1;
    private volatile long lastSystemVolumeMs = 0L;
    private final String bgmPath;
    private final boolean bgmAppleScriptAvailable;

    // Cached list of running applications at startup
    private volatile List<RunningApplication> runningApplications = EMPTY_APPS;

    public MacSndCtrl() {
        log.info("MacSndCtrl initialized on macOS – testing running application detection");
        this.bgmPath = locateBgm();
        this.bgmAppleScriptAvailable = hasBgmApp();
        if (bgmPath != null) {
            log.info("Background Music detected at {}", bgmPath);
        } else if (bgmAppleScriptAvailable) {
            log.info("Background Music app detected (AppleScript control available), but bgm CLI not found");
        } else {
            log.info("Background Music not found; using system volume fallback");
        }
        this.runningApplications = loadRunningApplications();
        log.info("MacSndCtrl initial running apps: {}", this.runningApplications);
    }

    private String locateBgm() {
        List<String> candidates = new ArrayList<>();
        var env = System.getenv("BGM_PATH");
        if (env != null) {
            candidates.add(env);
        }
        candidates.add("/opt/homebrew/bin/bgm");
        candidates.add("/usr/local/bin/bgm");

        // 'which bgm'
        var fromWhich = runAndCapture("which", "bgm");
        if (fromWhich != null && !fromWhich.isBlank()) {
            candidates.add(fromWhich.trim());
        }

        for (String c : candidates) {
            if (c == null || c.isBlank()) {
                continue;
            }
            File f = new File(c.trim());
            if (f.exists() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private boolean hasBgmApp() {
        List<String> candidates = new ArrayList<>();
        candidates.add("/Applications/Background Music.app");
        candidates.add(System.getProperty("user.home") + "/Applications/Background Music.app");
        return candidates.stream().anyMatch(p -> new File(p).exists());
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
        List<RunningApplication> apps = loadRunningApplicationsFromBgm();
        if (apps.isEmpty() && bgmAppleScriptAvailable) {
            apps = loadRunningApplicationsFromBgmAppleScript();
        }
        if (apps.isEmpty()) {
            return EMPTY_SESSIONS;
        }
        AtomicInteger fallbackPid = new AtomicInteger(0);
        return apps.stream()
                   .map(app -> {
                       int pid = app.pid() > 0 ? app.pid() : -fallbackPid.incrementAndGet();
                       return new AudioSession(null, pid, app.file(), app.name(), null, 1f, false);
                   })
                   .toList();
    }

    @Override
    public AudioDevice getDevice(String id) {
        return null;
    }

    @Override
    public void setDeviceVolume(String deviceId, float volume) {
        // macOS does not expose per-device volume without a native helper; drive the master output instead.
        setSystemOutputVolume(volume);
    }

    @Override
    public void muteDevice(String deviceId, MuteType mute) {
        setSystemMute(mute == MuteType.mute);
    }

    @Override
    public void setDefaultDevice(String deviceId) {
        log.debug("setDefaultDevice({}) – not implemented on macOS yet", deviceId);
    }

    // ---------- ISndCtrl: per-process / focus ----------

    @Override
    public void setProcessVolume(String fileName, String device, float volume) {
        if (bgmPath != null && setBgmVolume(fileName, volume)) {
            return;
        }
        if (bgmAppleScriptAvailable && setBgmVolumeViaAppleScript(fileName, volume)) {
            return;
        }
        // Without per-app audio control, fall back to master volume so the dial still does something useful.
        setSystemOutputVolume(volume);
    }

    @Override
    public void setFocusVolume(float volume) {
        if (bgmPath != null) {
            var focus = getFocusApplication();
            if (focus != null && setBgmVolume(focus, volume)) {
                return;
            }
        }
        if (bgmAppleScriptAvailable) {
            var focus = getFocusApplication();
            if (focus != null && setBgmVolumeViaAppleScript(focus, volume)) {
                return;
            }
        }
        setSystemOutputVolume(volume);
    }

    @Override
    public void muteProcesses(Set<String> fileNames, MuteType mute) {
        if (bgmPath != null) {
            boolean handled = false;
            for (String name : fileNames) {
                handled = setBgmMute(name, mute == MuteType.mute) || handled;
            }
            if (handled) {
                return;
            }
        }
        if (bgmAppleScriptAvailable) {
            boolean handled = false;
            for (String name : fileNames) {
                handled = setBgmMuteViaAppleScript(name, mute == MuteType.mute) || handled;
            }
            if (handled) {
                return;
            }
        }
        setSystemMute(mute == MuteType.mute);
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
                    var path = resolveBundlePath(bundleId);
                    String result = path != null ? path.getAbsolutePath() : bundleId;
                    log.debug("Frontmost application (macOS): {}", result);
                    return result;
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
        // Refresh on every call so the UI stays in sync with current running apps
        runningApplications = loadRunningApplications();
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

                File executable = resolveExecutable(bundle, pid, name);
                apps.add(new RunningApplication(pid, executable, name));
            }

            log.debug("Parsed running applications from AppleScript: {}", apps);
            return apps;
        } catch (Exception e) {
            log.error("Error querying running apps via AppleScript", e);
            return EMPTY_APPS;
        }
    }

    private List<RunningApplication> loadRunningApplicationsFromBgm() {
        List<RunningApplication> apps = new ArrayList<>();
        if (bgmPath == null) {
            return bgmAppleScriptAvailable ? loadRunningApplicationsFromBgmAppleScript() : apps;
        }

        String output = runBgmAndCapture("list-apps", "--json");
        if (output == null || output.isBlank()) {
            output = runBgmAndCapture("--list-apps", "--json"); // alternate ordering
        }
        if (output == null || output.isBlank()) {
            output = runBgmAndCapture("list-apps"); // fallback to text
        }

        if (output == null || output.isBlank()) {
            return apps;
        }

        boolean parsed = false;
        try {
            JsonNode root = MAPPER.readTree(output);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    int pid = firstInt(node, "pid", "processId", "processID");
                    if (pid < 0) {
                        pid = 0;
                    }
                    String bundle = firstNonBlank(node.path("bundleId").asText(null),
                                                  node.path("bundleID").asText(null),
                                                  node.path("id").asText(null));
                    String name = firstNonBlank(node.path("name").asText(null),
                                                node.path("title").asText(null),
                                                bundle);
                    String execPath = node.path("executablePath").asText(null);

                    File executable = null;
                    if (execPath != null && !execPath.isBlank()) {
                        var f = new File(execPath);
                        if (f.exists()) {
                            executable = f;
                        }
                    }
                    if (executable == null) {
                        executable = resolveBundlePath(bundle);
                    }
                    if (executable == null && bundle != null && bundle.contains(File.separator)) {
                        var f = new File(bundle);
                        if (f.exists()) {
                            executable = f;
                        }
                    }
                    if (executable == null) {
                        executable = new File(name != null ? name : ("pid-" + pid));
                    }

                    apps.add(new RunningApplication(pid, executable, name != null ? name : executable.getName()));
                }
                parsed = true;
            }
        } catch (Exception e) {
            log.debug("Failed to parse bgm list-apps json", e);
        }

        if (parsed) {
            return apps;
        }

        // Very simple text fallback: assume first token is pid, second is name
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                int pid = 0;
                String name;
                if (parts.length >= 2) {
                    try {
                        pid = Integer.parseInt(parts[0]);
                        name = parts[1];
                    } catch (NumberFormatException ex) {
                        name = parts[0];
                    }
                } else {
                    name = parts[0];
                }
                File executable = resolveExecutable(null, pid, name);
                apps.add(new RunningApplication(pid, executable, name));
            }
        } catch (IOException e) {
            log.debug("Failed to parse bgm text output", e);
        }

        return apps;
    }

    private List<RunningApplication> loadRunningApplicationsFromBgmAppleScript() {
        List<RunningApplication> apps = new ArrayList<>();
        if (!bgmAppleScriptAvailable) {
            return apps;
        }

        String script = """
            set outText to ""
            tell application "Background Music"
                set nameList to name of every audio application
                set bundleList to bundleID of every audio application
            end tell
            set len to (count of nameList)
            repeat with i from 1 to len
                set outText to outText & (item i of nameList) & "|" & (item i of bundleList) & linefeed
            end repeat
            return outText
            """;

        String output = runAndCapture("osascript", "-e", script);
        if (output == null || output.isBlank()) {
            return apps;
        }

        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 2);
            String name = parts[0];
            String bundle = parts.length > 1 ? parts[1] : null;
            File exec = resolveBundlePath(bundle);
            if (exec == null) {
                exec = new File(name);
            }
            apps.add(new RunningApplication(0, exec, name));
        }

        return apps;
    }

    /**
     * Try AppleScript first (best names/icons), then fall back to ps-based discovery
     * so we still show something even if accessibility permissions are missing.
     */
    private List<RunningApplication> loadRunningApplications() {
        var fromBgm = loadRunningApplicationsFromBgm();
        if (!fromBgm.isEmpty()) {
            return fromBgm;
        }

        var fromAppleScript = loadRunningApplicationsFromAppleScript();
        if (!fromAppleScript.isEmpty()) {
            return fromAppleScript;
        }

        var fromLsappinfo = loadRunningApplicationsFromLsappinfo();
        if (!fromLsappinfo.isEmpty()) {
            return fromLsappinfo;
        }

        var fromPs = loadRunningApplicationsFromPs();
        if (fromPs.isEmpty()) {
            log.warn("No running applications detected via AppleScript or ps fallback");
            return EMPTY_APPS;
        }

        log.debug("Using ps fallback for running applications: {}", fromPs);
        return fromPs;
    }

    private List<RunningApplication> loadRunningApplicationsFromLsappinfo() {
        List<RunningApplication> apps = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("lsappinfo", "list").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isBlank()) {
                        continue;
                    }

                    Matcher pidM = LSAPP_PID.matcher(line);
                    Matcher nameM = LSAPP_NAME.matcher(line);
                    Matcher execM = LSAPP_EXEC.matcher(line);
                    Matcher bundleM = LSAPP_BUNDLE.matcher(line);

                    if (!pidM.find()) {
                        continue;
                    }
                    int pid = Integer.parseInt(pidM.group(1));

                    String name = nameM.find() ? nameM.group(1) : null;
                    String execPath = execM.find() ? execM.group(1) : null;
                    String bundleId = bundleM.find() ? bundleM.group(1) : null;

                    File executable = null;
                    if (execPath != null) {
                        File path = new File(execPath);
                        if (path.exists()) {
                            executable = path;
                        }
                    }
                    if (executable == null) {
                        executable = resolveBundlePath(bundleId);
                    }
                    if (executable == null) {
                        continue;
                    }

                    apps.add(new RunningApplication(pid, executable, name != null ? name : executable.getName()));
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.debug("lsappinfo-based running-app fallback failed", e);
        }

        return apps;
    }

    /**
     * Very lightweight fallback that uses ps to find foreground-style apps (.app bundles).
     */
    private List<RunningApplication> loadRunningApplicationsFromPs() {
        List<RunningApplication> apps = new ArrayList<>();
        try {
            // Grab pid + user + full command so we can filter to the current user
            Process p = new ProcessBuilder("ps", "-Ao", "pid=,user=,command=").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] split = line.split("\\s+", 3);
                    if (split.length < 3) {
                        continue;
                    }
                    String pidStr = split[0];
                    String user = split[1];
                    String cmdLine = split[2];

                    // Ignore other users/system daemons; we just want the current user's apps
                    if (!System.getProperty("user.name", "").equals(user)) {
                        continue;
                    }

                    int pid;
                    try {
                        pid = Integer.parseInt(pidStr);
                    } catch (NumberFormatException ex) {
                        continue;
                    }

                    // Try to capture a full .app path even when it has spaces
                    File exec = extractExecutablePath(cmdLine);
                    if (exec == null) {
                        continue;
                    }
                    if (!exec.exists()) {
                        continue;
                    }

                    // No longer require ".app" in the path; show whatever the user owns
                    String name = exec.getName();
                    apps.add(new RunningApplication(pid, exec, name));
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.warn("ps-based running-app fallback failed", e);
        }

        // Deduplicate by executable path to keep the list short for the picker
        return apps.stream()
                   .collect(Collectors.toMap(ra -> ra.file().getAbsolutePath(), ra -> ra, (a, b) -> a))
                   .values()
                   .stream()
                   .toList();
    }

    private File extractExecutablePath(String cmdLine) {
        // Prefer full .app bundle path if present
        Matcher m = APP_PATH.matcher(cmdLine);
        if (m.find()) {
            var path = new File(m.group(1));
            if (path.exists()) {
                return path;
            }
        }

        // Fallback: first token (may be truncated if there were spaces)
        String[] parts = cmdLine.split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        var path = new File(parts[0]);
        if (path.exists()) {
            return path;
        }

        return null;
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

    private File resolveExecutable(String bundleId, int pid, String name) {
        // Prefer bundle path (fast + stable), fallback to ps command, then name as last resort.
        var byBundle = resolveBundlePath(bundleId);
        if (byBundle != null) {
            return byBundle;
        }

        var fromPs = resolveProcessPath(pid);
        if (fromPs != null) {
            return fromPs;
        }

        return new File(name);
    }

    private File resolveBundlePath(String bundleId) {
        if (bundleId == null || bundleId.isBlank() || "missing value".equalsIgnoreCase(bundleId)) {
            return null;
        }

        var cached = bundleIdPathCache.get(bundleId);
        if (cached != null && cached.exists()) {
            return cached;
        }

        List<String> candidates = new ArrayList<>();
        Collections.addAll(candidates, "/Applications", System.getProperty("user.home") + "/Applications");

        for (String base : candidates) {
            var found = runAndCapture("mdfind", "-onlyin", base, "kMDItemCFBundleIdentifier == \"" + bundleId + "\"");
            if (found != null && !found.isBlank()) {
                var path = new File(found.trim());
                if (path.exists()) {
                    bundleIdPathCache.put(bundleId, path);
                    return path;
                }
            }
        }

        return null;
    }

    private File resolveProcessPath(int pid) {
        var output = runAndCapture("ps", "-p", Integer.toString(pid), "-o", "command=");
        if (output == null || output.isBlank()) {
            return null;
        }

        // The command line is first token; strip after whitespace so /Applications/Foo.app/Contents/MacOS/Foo --flag → path
        String cmdLine = output.trim();
        int spaceIdx = cmdLine.indexOf(' ');
        String pathPart = spaceIdx > 0 ? cmdLine.substring(0, spaceIdx) : cmdLine;
        File path = new File(pathPart);
        if (path.exists()) {
            return path;
        }
        return null;
    }

    private void setSystemOutputVolume(float volume) {
        int vol = Math.max(0, Math.min(100, Math.round(volume * 100)));

        // Avoid spamming osascript when the value has not changed or only changed by a tiny amount very quickly
        long now = System.currentTimeMillis();
        if (vol == lastSystemVolume) {
            return;
        }
        if (now - lastSystemVolumeMs < 35 && Math.abs(vol - lastSystemVolume) < 2) {
            return;
        }

        lastSystemVolume = vol;
        lastSystemVolumeMs = now;

        run("osascript", "-e", "set volume output volume " + vol + " without output muted");
    }

    private void setSystemMute(boolean mute) {
        String script = mute ? "set volume with output muted" : "set volume output muted false";
        run("osascript", "-e", script);
    }

    private boolean setBgmVolume(String target, float volume) {
        if (bgmPath == null) {
            return false;
        }
        String normalized = normalizeTarget(target);
        if (normalized == null) {
            return false;
        }
        String volStr = String.format(Locale.US, "%.3f", Math.max(0f, Math.min(1f, volume)));
        return runBgmCommand("set-app-volume", normalized, volStr);
    }

    private boolean setBgmMute(String target, boolean mute) {
        if (bgmPath == null) {
            return false;
        }
        String normalized = normalizeTarget(target);
        if (normalized == null) {
            return false;
        }
        String muteStr = mute ? "on" : "off";
        return runBgmCommand("set-app-mute", normalized, muteStr);
    }

    private boolean setBgmVolumeViaAppleScript(String target, float volume) {
        if (!bgmAppleScriptAvailable) {
            return false;
        }
        String normalized = normalizeTarget(target);
        if (normalized == null) {
            return false;
        }
        int vol = Math.max(0, Math.min(100, Math.round(volume * 100)));
        String escaped = escapeAppleScriptString(normalized);
        String script = """
            tell application "Background Music"
                repeat with a in audio applications
                    if (bundleID of a is "%s") or (name of a is "%s") then
                        set vol of a to %d
                        return "ok"
                    end if
                end repeat
            end tell
            return "notfound"
            """.formatted(escaped, escaped, vol);
        String output = runAndCapture("osascript", "-e", script);
        return output != null && output.contains("ok");
    }

    private boolean setBgmMuteViaAppleScript(String target, boolean mute) {
        if (!bgmAppleScriptAvailable) {
            return false;
        }
        String normalized = normalizeTarget(target);
        if (normalized == null) {
            return false;
        }
        int vol = mute ? 0 : 100;
        return setBgmVolumeViaAppleScript(normalized, vol / 100f);
    }

    private String normalizeTarget(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }

        // If a path is provided, try to turn it into a bundle identifier (preferred by bgm)
        if (target.contains(File.separator)) {
            File f = new File(target);
            if (f.exists()) {
                var bid = bundleIdentifierFromPath(f);
                if (bid != null) {
                    return bid;
                }
                return f.getName();
            }
        }

        // If it already looks like a bundle id, use it as-is
        if (target.contains(".")) {
            return target;
        }

        return target;
    }

    private String bundleIdentifierFromPath(File app) {
        if (app == null || !app.exists()) {
            return null;
        }
        var output = runAndCapture("mdls", "-raw", "-name", "kMDItemCFBundleIdentifier", app.getAbsolutePath());
        if (output == null || output.isBlank()) {
            return null;
        }
        var trimmed = output.trim();
        if ("(null)".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private void run(String... cmd) {
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            log.warn("Command failed: {}", String.join(" ", cmd), e);
        }
    }

    private String runAndCapture(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null) {
                    StringBuilder sb = new StringBuilder(line);
                    String extra;
                    while ((extra = reader.readLine()) != null) {
                        sb.append('\n').append(extra);
                    }
                    p.waitFor();
                    return sb.toString();
                }
            }
            p.waitFor();
        } catch (IOException e) {
            log.warn("Command failed: {}", String.join(" ", cmd), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Command interrupted: {}", String.join(" ", cmd));
        }
        return null;
    }

    private record CommandResult(int exitCode, String output) {
    }

    private CommandResult runAndCaptureResult(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (!first) {
                        output.append('\n');
                    }
                    output.append(line);
                    first = false;
                }
            }
            int exitCode = p.waitFor();
            return new CommandResult(exitCode, output.toString());
        } catch (IOException e) {
            log.warn("Command failed: {}", String.join(" ", cmd), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Command interrupted: {}", String.join(" ", cmd));
        }
        return null;
    }

    private boolean runBgmCommand(String... args) {
        if (bgmPath == null) {
            return false;
        }
        String[] cmd = new String[args.length + 1];
        cmd[0] = bgmPath;
        System.arraycopy(args, 0, cmd, 1, args.length);
        CommandResult result = runAndCaptureResult(cmd);
        if (result == null) {
            return false;
        }
        if (result.exitCode() != 0) {
            log.warn("bgm command failed (exit {}): {}", result.exitCode(), String.join(" ", cmd));
            if (result.output() != null && !result.output().isBlank()) {
                log.warn("bgm output: {}", result.output());
            }
            return false;
        }
        return true;
    }

    private String runBgmAndCapture(String... args) {
        if (bgmPath == null) {
            return null;
        }
        String[] cmd = new String[args.length + 1];
        cmd[0] = bgmPath;
        System.arraycopy(args, 0, cmd, 1, args.length);
        CommandResult result = runAndCaptureResult(cmd);
        if (result == null) {
            return null;
        }
        if (result.exitCode() != 0) {
            log.warn("bgm command failed (exit {}): {}", result.exitCode(), String.join(" ", cmd));
            if (result.output() != null && !result.output().isBlank()) {
                log.warn("bgm output: {}", result.output());
            }
            return null;
        }
        return result.output();
    }

    private static String escapeAppleScriptString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static int firstInt(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return -1;
        }
        for (String field : fields) {
            if (field == null) {
                continue;
            }
            JsonNode value = node.get(field);
            if (value == null) {
                continue;
            }
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText());
                } catch (NumberFormatException ex) {
                    // ignore
                }
            }
        }
        return -1;
    }
}
