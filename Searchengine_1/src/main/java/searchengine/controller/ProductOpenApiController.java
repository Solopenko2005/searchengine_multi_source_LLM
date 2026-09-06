package searchengine.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/**
 * Makes internal module specifications available to the administrator's single
 * Swagger UI without exposing the e-mail service itself to the public network.
 */
@Hidden
@RestController
@RequestMapping("/api/admin/openapi")
public class ProductOpenApiController {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String authorizationSpecUrl;
    private final String emailSpecUrl;

    public ProductOpenApiController(WebClient.Builder webClientBuilder,
                                    ObjectMapper objectMapper,
                                    @Value("${app.docs.authorization-url}") String authorizationSpecUrl,
                                    @Value("${app.docs.email-url}") String emailSpecUrl) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.authorizationSpecUrl = authorizationSpecUrl;
        this.emailSpecUrl = emailSpecUrl;
    }

    @GetMapping(value = "/authorization", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> authorization() {
        return ResponseEntity.ok(loadAndAdjust(authorizationSpecUrl, "/auth-api",
                "Публичный API авторизации. Защищённые операции используют Bearer JWT."));
    }

    @GetMapping(value = "/email", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> email() {
        return ResponseEntity.ok(loadAndAdjust(emailSpecUrl, "http://email:8771",
                "Внутренний сервис Docker. Он документируется здесь, но намеренно не опубликован в интернете."));
    }

    private JsonNode loadAndAdjust(String url, String serverUrl, String description) {
        try {
            JsonNode received = webClient.get().uri(url).retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(10));
            if (received == null || !received.isObject()) {
                throw new IllegalStateException("Пустая OpenAPI-спецификация");
            }
            ObjectNode result = ((ObjectNode) received).deepCopy();
            ArrayNode servers = objectMapper.createArrayNode();
            servers.addObject().put("url", serverUrl).put("description", description);
            result.set("servers", servers);
            return result;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Спецификация внутреннего модуля временно недоступна", exception);
        }
    }
}
