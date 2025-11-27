package com.getpcpanel.commands;

import com.getpcpanel.iconextract.IIconService;
import com.getpcpanel.iconextract.MacNoopIconService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * macOS icon config: always provides a fallback IIconService so the app
 * can start even when the Windows icon extractor is unavailable.
 */
@Configuration
public class MacIconConfig {

    @Bean
    @Primary
    public IIconService macNoopIconService() {
        // Always provide this implementation; on Windows you can later gate this
        // behind an OS check if needed.
        return new MacNoopIconService();
    }
}
