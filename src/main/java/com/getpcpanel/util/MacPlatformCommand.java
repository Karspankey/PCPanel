package com.getpcpanel.util;

import java.io.File;
import java.io.IOException;

import org.springframework.stereotype.Service;

import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
public class MacPlatformCommand extends IPlatformCommand {

    @Override
    public void exec(String shortcut) {
        var file = new File(shortcut);
        try {
            if (file.isDirectory()) {
                // Open a folder in Finder
                rt.exec(new String[] { "open", file.getAbsolutePath() });
            } else if (file.isFile() && Util.isFileExecutable(file)) {
                // Run an executable directly
                rt.exec(new String[] { file.getAbsolutePath() });
            } else {
                // Let macOS decide (apps in /Applications, URLs, docs, etc.)
                rt.exec(new String[] { "open", shortcut });
            }
        } catch (IOException e) {
            log.error("Unable to run {}", shortcut, e);
        }
    }

    @Override
    public void kill(String process) {
        // For now we don't have the equivalent of sndCtrl.getFocusApplication() on mac
        if (FOCUS.equals(process)) {
            log.warn("FOCUS kill not implemented on macOS; ignoring request.");
            return;
        }

        String toKill = new File(process).getName(); // drop any path
        try {
            // -f matches against the full command line, which is handy for app names
            rt.exec(new String[] { "pkill", "-f", toKill });
        } catch (IOException e) {
            log.error("Unable to end '{}'", toKill, e);
        }
    }
}
