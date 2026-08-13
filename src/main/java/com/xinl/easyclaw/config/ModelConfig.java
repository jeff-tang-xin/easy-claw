package com.xinl.easyclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    /* TODO: migrate to Embabel - AgentScopeProperties removed */
    public ModelConfig() {
        log.info("ModelConfig initialized (AgentScopeProperties removed, pending Embabel migration)");
    }
}
