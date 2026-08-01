package com.seckill.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonUtilsTest {

    @Test
    void roundTripShouldPreserveFields() {
        Demo demo = new Demo("seckill", 100);
        String json = JsonUtils.toJson(demo);
        Demo parsed = JsonUtils.fromJson(json, Demo.class);
        assertEquals("seckill", parsed.getName());
        assertEquals(100, parsed.getCount());
    }

    @Test
    void unknownFieldsShouldBeIgnored() {
        Demo parsed = JsonUtils.fromJson("{\"name\":\"a\",\"count\":1,\"unknown\":true}", Demo.class);
        assertEquals("a", parsed.getName());
    }

    @Test
    void genericTypeShouldBeSupported() {
        List<Demo> list = JsonUtils.fromJson(
                "[{\"name\":\"a\",\"count\":1}]",
                new TypeReference<List<Demo>>() {
                });
        assertEquals(1, list.size());
        assertEquals("a", list.get(0).getName());
    }

    @Test
    void invalidJsonShouldWrapBusinessException() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> JsonUtils.fromJson("{invalid", Demo.class));
        assertEquals(ErrorCode.JSON_ERROR, e.getErrorCode());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Demo {
        private String name;
        private int count;
    }
}
