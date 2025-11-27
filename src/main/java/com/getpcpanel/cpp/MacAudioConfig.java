package com.getpcpanel.cpp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration to provide a fallback ISndCtrl implementation
 * on platforms where the native SndCtrl backend is not available.
 */
@Configuration
public class MacAudioConfig {

    @Bean
    @ConditionalOnMissingBean(ISndCtrl.class)
    public ISndCtrl macNoopSndCtrl() {
        return new MacNoopSndCtrl();
    }
}
