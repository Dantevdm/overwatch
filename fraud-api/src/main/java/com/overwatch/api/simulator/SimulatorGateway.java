package com.overwatch.api.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Talks to the transaction simulator's control API on the dashboard's behalf.
 *
 * <p>The simulator publishes no host port — only Prometheus and this service reach
 * it, over the compose network. Routing its controls through the API keeps the
 * browser on one origin, so the dashboard needs no second base URL, no CORS
 * configuration on the simulator, and no port to open.
 *
 * <p>A simulator that is down is an ordinary state, not a server fault: the rest of
 * the stack is useful without it. So an unreachable simulator becomes a 503
 * carrying a sentence the dashboard can show, rather than a stack trace.
 */
@Component
public class SimulatorGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatorGateway.class);

    private static final ParameterizedTypeReference<Map<String, Object>> BODY =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;

    public SimulatorGateway(@Value("${overwatch.api.simulator-url:http://localhost:8081}")
                            String baseUrl) {
        // Built directly rather than from an injected RestClient.Builder: Spring
        // Boot 4 moved that builder behind its own starter, and one outbound
        // client to one known service does not justify pulling it in.
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        log.info("Simulator control proxied to {}", baseUrl);
    }

    public Map<String, Object> get(String path) {
        return call(() -> client.get().uri(path).retrieve().body(BODY));
    }

    public Map<String, Object> post(String path) {
        return call(() -> client.post().uri(path).retrieve().body(BODY));
    }

    private Map<String, Object> call(Supplier<Map<String, Object>> action) {
        try {
            Map<String, Object> body = action.get();
            return body == null ? Map.of() : body;
        } catch (RestClientException e) {
            log.warn("Simulator control call failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The transaction simulator is not reachable. It may still be starting, "
                            + "or it is not running in this stack.");
        }
    }
}
