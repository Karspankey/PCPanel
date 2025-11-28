package com.getpcpanel.util;

import java.io.File;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
public class FileUtil {
    @Value("${application.root:${user.home}/.pcpanel}") private String rootPath;
    private File root;

    @PostConstruct
    void ensureRoot() {
        root = new File(rootPath);
        log.info("Using root: {}", root);
        if (!root.exists() && !root.mkdirs()) {
            log.error("Unable to create file root: {}", root);
        }
    }

    public File getFile(String file) {
        return new File(root, file);
    }
}
