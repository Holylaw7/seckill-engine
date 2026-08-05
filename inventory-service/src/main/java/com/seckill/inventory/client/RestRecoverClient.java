package com.seckill.inventory.client;

import com.seckill.common.result.Result;
import com.seckill.inventory.config.InventoryProperties;
import com.seckill.inventory.dto.RecoverRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.seckill.common.security.InternalSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class RestRecoverClient implements RecoverClient {

    private final RestClient recoverRestClient;
    private final InventoryProperties properties;

    @Value("${seckill.internal-auth.client-name:inventory-service}")
    private String clientName;

    @Value("${seckill.internal-auth.client-secret:dev-inventory-secret}")
    private String clientSecret;

    @Override
    public boolean recover(RecoverRequest request) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            Result<Void> result = recoverRestClient.post()
                    .uri(properties.getRecover().getPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Service-Name", clientName)
                    .header("X-Service-Timestamp", timestamp)
                    .header("X-Service-Signature",
                            InternalSignature.sign(clientSecret, clientName + ":" + timestamp))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Result<Void>>() {
                    });
            return result != null && result.getCode() == 0;
        } catch (Exception e) {
            log.warn("redis recover call failed, requestId={}, skuId={}, error={}",
                    request.getRequestId(), request.getSkuId(), e.getMessage());
            return false;
        }
    }
}
