package com.airca.chaos;

import com.airca.dto.SimulateOutageRequest;
import jakarta.validation.Valid;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ChaosController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChaosController.class);
    private final ChaosSimulatorService chaosSimulatorService;

    public ChaosController(ChaosSimulatorService chaosSimulatorService) {
        this.chaosSimulatorService = chaosSimulatorService;
    }

    @PostMapping("/simulate-outage")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChaosSimulationResponse simulateOutage(@Valid @RequestBody SimulateOutageRequest request) {
        String scenario;
        try {
            scenario = chaosSimulatorService.normalizeScenario(request.scenario());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        chaosSimulatorService.simulate(scenario, request.tenantId(), request.logCount())
                .whenComplete((logsQueued, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null
                                ? error.getCause()
                                : error;
                        LOGGER.error("Chaos simulation failed for scenario {}", scenario, cause);
                    }
                });
        return new ChaosSimulationResponse("Chaos simulation triggered", scenario, request.logCount());
    }

    public record ChaosSimulationResponse(String message, String scenario, int logsQueued) {
    }
}
