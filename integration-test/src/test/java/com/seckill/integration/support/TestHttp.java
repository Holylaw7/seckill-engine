package com.seckill.integration.support;

import com.seckill.common.result.Result;
import com.seckill.common.util.JsonUtils;
import com.seckill.auth.dto.LoginRequest;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.inventory.dto.ReconcileReport;
import com.seckill.order.dto.OrderDetailResponse;
import com.seckill.payment.dto.CreatePayRequest;
import com.seckill.payment.dto.CreatePayResponse;
import com.seckill.seckill.dto.ExecuteRequest;
import com.seckill.seckill.dto.ExecuteResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集成测试 HTTP 客户端：秒杀、支付、订单取消、回调签名。
 */
public final class TestHttp {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final RestTemplate REST;
    private static final RestTemplate CALLBACK_REST;

    static {
        // 确定性超时：JDK HttpClient 读超时在持续负载下未可靠生效，改用 HttpURLConnection（keep-alive 复用）
        SimpleClientHttpRequestFactory restFactory = new SimpleClientHttpRequestFactory();
        restFactory.setConnectTimeout(10_000);
        restFactory.setReadTimeout(15_000);
        REST = new RestTemplate(restFactory);
        CALLBACK_REST = new RestTemplate(restFactory);
        // 回调接口以 HTTP 4xx 表达业务拒绝，客户端需要拿到状态码而非抛异常
        CALLBACK_REST.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
                // 4xx 由调用方按状态码断言
            }
        });
    }

    private TestHttp() {
    }

    public static Result<ExecuteResponse> execute(String baseUrl, long userId,
                                                  long sessionId, long skuId,
                                                  int quantity, String traceId) {
        return executeWithAuth(baseUrl, userId, sessionId, skuId, quantity, traceId, null);
    }

    public static Result<ExecuteResponse> executeWithAuth(String baseUrl, long userId,
                                                          long sessionId, long skuId,
                                                          int quantity, String traceId, String token) {
        ExecuteRequest request = new ExecuteRequest();
        request.setSessionId(sessionId);
        request.setSkuId(skuId);
        request.setQuantity(quantity);
        HttpHeaders headers = headers(userId, traceId);
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return REST.exchange(baseUrl + "/api/v1/seckill/execute", HttpMethod.POST,
                new HttpEntity<>(request, headers),
                new ParameterizedTypeReference<Result<ExecuteResponse>>() {
                }).getBody();
    }

    /** 返回原始响应（含 HTTP 状态码），供限流/容量基准使用 */
    public static ResponseEntity<String> executeRaw(String baseUrl, long userId,
                                                    long sessionId, long skuId,
                                                    int quantity, String traceId, String token) {
        return executeRaw(REST, baseUrl, userId, sessionId, skuId, quantity, traceId, token);
    }

    /** 4xx 不抛异常的原始执行（限流 429 需按状态码统计） */
    public static ResponseEntity<String> executeRawNoError(String baseUrl, long userId,
                                                           long sessionId, long skuId,
                                                           int quantity, String traceId, String token) {
        return executeRaw(CALLBACK_REST, baseUrl, userId, sessionId, skuId, quantity, traceId, token);
    }

    private static ResponseEntity<String> executeRaw(RestTemplate rest, String baseUrl, long userId,
                                                      long sessionId, long skuId,
                                                      int quantity, String traceId, String token) {
        ExecuteRequest request = new ExecuteRequest();
        request.setSessionId(sessionId);
        request.setSkuId(skuId);
        request.setQuantity(quantity);
        HttpHeaders headers = headers(userId, traceId);
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(baseUrl + "/api/v1/seckill/execute", HttpMethod.POST,
                new HttpEntity<>(request, headers), String.class);
    }

    public static Result<LoginResponse> login(String baseUrl, String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return REST.exchange(baseUrl + "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(new LoginRequest(username, password), headers),
                new ParameterizedTypeReference<Result<LoginResponse>>() {
                }).getBody();
    }

    /** GET 原始响应体（供可观测性/健康检查） */
    public static String getRaw(String url) {
        return CALLBACK_REST.getForObject(url, String.class);
    }

    public static Result<OrderDetailResponse> getOrder(String baseUrl, long userId,
                                                       String orderId, String traceId) {
        return REST.exchange(baseUrl + "/api/v1/orders/" + orderId, HttpMethod.GET,
                new HttpEntity<>(null, headers(userId, traceId)),
                new ParameterizedTypeReference<Result<OrderDetailResponse>>() {
                }).getBody();
    }

    public static Result<CreatePayResponse> createPayment(String baseUrl, String orderNo,
                                                          long userId, String amount) {
        CreatePayRequest request = new CreatePayRequest();
        request.setOrderNo(orderNo);
        request.setUserId(userId);
        request.setAmount(new BigDecimal(amount));
        request.setChannel("MOCK");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return REST.exchange(baseUrl + "/api/v1/payments/create", HttpMethod.POST,
                new HttpEntity<>(request, headers),
                new ParameterizedTypeReference<Result<CreatePayResponse>>() {
                }).getBody();
    }

    public static Result<Void> cancelOrder(String baseUrl, long userId, String orderId, String traceId) {
        return REST.exchange(baseUrl + "/api/v1/orders/" + orderId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(null, headers(userId, traceId)),
                new ParameterizedTypeReference<Result<Void>>() {
                }).getBody();
    }

    public static Result<ReconcileReport> reconcile(String baseUrl, long skuId) {
        return REST.exchange(baseUrl + "/api/v1/inventory/admin/reconcile?skuId=" + skuId, HttpMethod.GET,
                null, new ParameterizedTypeReference<Result<ReconcileReport>>() {
                }).getBody();
    }

    public static ResponseEntity<String> callback(String baseUrl, String paymentNo, String transactionNo,
                                                  String amount, long timestamp, String sign, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Pay-Sign", sign);
        headers.set("X-Pay-Timestamp", String.valueOf(timestamp));
        headers.set("X-Trace-Id", traceId);
        String body = JsonUtils.toJson(callbackPayload(paymentNo, transactionNo, amount, timestamp));
        return CALLBACK_REST.exchange(baseUrl + "/api/v1/payments/callback/MOCK", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    public static String signCallback(String paymentNo, String transactionNo, String amount,
                                      long timestamp, String secret) {
        return hmacSha256(paymentNo + "|" + transactionNo + "|" + amount + "|" + timestamp, secret);
    }

    public static String createOrderMessageJson(String messageId, long userId, long sessionId, long skuId,
                                                String orderId, int quantity, long amountFen, String traceId) {
        return createOrderMessageJson(messageId, userId, sessionId, skuId, orderId,
                quantity, amountFen, traceId, null);
    }

    public static String createOrderMessageJson(String messageId, long userId, long sessionId, long skuId,
                                                String orderId, int quantity, long amountFen, String traceId,
                                                Integer bucketNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("userId", userId);
        payload.put("skuId", skuId);
        payload.put("sessionId", sessionId);
        payload.put("orderId", orderId);
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("quantity", quantity);
        payload.put("amount", amountFen);
        payload.put("traceId", traceId);
        if (bucketNo != null) {
            payload.put("bucketNo", bucketNo);
        }
        return JsonUtils.toJson(payload);
    }

    public static String cancelOrderMessageJson(String messageId, String orderId, long userId,
                                                long sessionId, long skuId, int quantity, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("orderId", orderId);
        payload.put("userId", userId);
        payload.put("skuId", skuId);
        payload.put("sessionId", sessionId);
        payload.put("quantity", quantity);
        payload.put("reason", reason);
        payload.put("timestamp", System.currentTimeMillis());
        return JsonUtils.toJson(payload);
    }

    private static Map<String, Object> callbackPayload(String paymentNo, String transactionNo,
                                                       String amount, long timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentNo", paymentNo);
        payload.put("channelTransactionNo", transactionNo);
        payload.put("amount", amount);
        payload.put("status", "SUCCESS");
        payload.put("timestamp", timestamp);
        return payload;
    }

    private static HttpHeaders headers(long userId, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("X-Trace-Id", traceId);
        return headers;
    }

    private static String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("mock sign failed", e);
        }
    }
}
