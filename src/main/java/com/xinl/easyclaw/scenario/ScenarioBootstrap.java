package com.xinl.easyclaw.scenario;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev-sqlite")
public class ScenarioBootstrap implements CommandLineRunner {

    private final ScenarioService scenarioService;

    public ScenarioBootstrap(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @Override
    public void run(String... args) {
        scenarioService.ensurePresets();
    }
}
