package com.getpcpanel.iconextract;

import java.awt.image.BufferedImage;
import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * macOS placeholder implementation of IIconService.
 * Lets PCPanel start on macOS where the Windows icon extractor is unavailable.
 * All methods are currently no-ops or return simple defaults.
 */

public class MacNoopIconService implements IIconService {
    private static final Logger log = LoggerFactory.getLogger(MacNoopIconService.class);

    public MacNoopIconService() {
        log.warn("Using MacNoopIconService: icon extraction is not implemented on macOS yet.");
    }

    @Override
    public BufferedImage getIconForFile(int width, int height, File file) {
        log.warn("getIconForFile({}, {}, {}) called on MacNoopIconService – not implemented",
             width, height, file);
        return null; // or you could return a tiny blank image if you prefer
    }


    // We'll let VS Code generate the interface methods next.
}
