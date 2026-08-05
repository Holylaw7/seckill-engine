package com.seckill.order.client;

import com.seckill.common.result.Result;
import com.seckill.order.config.OrderProperties;
import com.seckill.order.dto.PreDeductConfirmRequest;
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
public class RestPreDeductConfirmClient implements PreDeductConfirmClient {

    private final RestClient preDeductConfirmRestClient;
    private final OrderProperties properties;

    @Value("${seckill.internal-auth.client-name:order-service}")
    private String clientName;

    @Value("${seckill.internal-auth.client-secret:dev-order-secret}")
    private String clientSecret;

    @Override
    public boolean confirm(String messageId, String orderId) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            Result<Void> result = preDeductConfirmRestClient.post()
                    .uri(properties.getPreDeductConfirm().getPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Service-Name", clientName)
                    .header("X-Service-Timestamp", timestamp)
                    .header("X-Service-Signature",
                            InternalSignature.sign(clientSecret, clientName + ":" + timestamp))
                    .body(new PreDeductConfirmRequest(messageId, orderId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Result<Void>>() {
                    });
            return result != null && result.getCode() == 0;
        } catch (Exception e) {
            log.warn("pre-deduct confirm failed, messageId={}, orderId={}, error={}",
                    messageId, orderId, e.getMessage());
            return false;
        }
    }
}
