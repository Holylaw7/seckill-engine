package com.seckill.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;

/**
 * 基于 Jackson 的 JSON 工具，异常统一包装为 {@link BusinessException}(JSON_ERROR)。
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .registerModule(new JavaTimeModule());

    private JsonUtils() {
    }

    public static String toJson(Object object) {
        try {
            return MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.JSON_ERROR, e);
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.JSON_ERROR, e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.JSON_ERROR, e);
        }
    }
}
