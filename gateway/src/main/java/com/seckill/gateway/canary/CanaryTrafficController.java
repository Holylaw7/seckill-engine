package com.seckill.gateway.canary;

import com.seckill.gateway.config.CanaryTrafficProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 6.7 Canary 权重控制端点（WebFlux）。
 *
 * <p>生产默认 controlEnabled=false 不开放；演练/运维平台开启后需携带 X-Canary-Token。</p>
 */
@RestController
@RequestMapping("/actuator/canary")
@RequiredArgsConstructor
public class CanaryTrafficController {

    private final CanaryTrafficProperties properties;

    @GetMapping
    public Map<String, Object> status() {
        return snapshot();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> update(
            @RequestHeader(value = "X-Canary-Token", required = false) String token,
            @RequestBody CanaryUpdateRequest request) {
        if (!properties.isControlEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", 403,
                    "message", "canary control disabled"));
        }
        if (properties.getControlToken().isBlank()
                || !properties.getControlToken().equals(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", 401,
                    "message", "invalid canary token"));
        }
        if (request.weight() != null) {
            int weight = request.weight();
            if (weight < 0 || weight > 100) {
                return ResponseEntity.badRequest().body(Map.of("code", 400,
                        "message", "weight must be in [0,100]"));
            }
            properties.setWeight(weight);
        }
        if (request.enabled() != null) {
            properties.setEnabled(request.enabled());
        }
        if (request.version() != null && !request.version().isBlank()) {
            properties.setVersion(request.version());
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(snapshot());
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("enabled", properties.isEnabled());
        snapshot.put("weight", properties.getWeight());
        snapshot.put("version", properties.getVersion());
        snapshot.put("controlEnabled", properties.isControlEnabled());
        return snapshot;
    }

    public record CanaryUpdateRequest(Boolean enabled, Integer weight, String version) {
    }
}
