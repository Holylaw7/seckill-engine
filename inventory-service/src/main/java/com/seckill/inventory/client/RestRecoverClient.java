package com.seckill.inventory.client;

import com.seckill.common.result.Result;
import com.seckill.inventory.config.InventoryProperties;
import com.seckill.inventory.dto.RecoverRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Override
    public boolean recover(RecoverRequest request) {
        try {
            Result<Void> result = recoverRestClient.post()
                    .uri(properties.getRecover().getPath())
                    .contentType(MediaType.APPLICATION_JSON)
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
