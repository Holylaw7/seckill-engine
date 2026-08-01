package com.seckill.seckill.lua;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lua 脚本静态语义测试（G-11，冻结契约）：
 * 仅验证脚本资源契约，Lua 原子性并发验证属于 Phase 5.3/5.5。
 */
class LuaScriptTest {

    private static String load(String location) throws Exception {
        ClassPathResource resource = new ClassPathResource(location);
        assertThat(resource.exists()).as("lua script missing: %s", location).isTrue();
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void should_contain_all_frozen_result_codes_when_load_deduct_script() throws Exception {
        // Arrange & Act
        String script = load("lua/seckill_deduct.lua");

        // Assert
        assertThat(script).isNotBlank();
        assertThat(script).contains("v1.0");
        assertThat(script).contains("SUCCESS", "STOCK_EMPTY", "REPEAT_BUY", "NOT_READY");
    }

    @Test
    void should_keep_frozen_keys_and_args_when_load_deduct_script() throws Exception {
        // Arrange & Act
        String script = load("lua/seckill_deduct.lua");

        // Assert
        assertThat(script).contains("KEYS[1]", "KEYS[2]", "ARGV[1]", "ARGV[2]");
        assertThat(script).contains("seckill:stock:", "seckill:user:");
        assertThat(script).contains("EXISTS", "DECRBY", "SET", "'EX'");
    }

    @Test
    void should_contain_recover_guard_when_load_recover_script() throws Exception {
        // Arrange & Act
        String script = load("lua/seckill_recover.lua");

        // Assert
        assertThat(script).isNotBlank();
        assertThat(script).contains("v1.0");
        assertThat(script).contains("SUCCESS", "OVER_TOTAL");
        assertThat(script).contains("KEYS[1]", "KEYS[2]", "KEYS[3]", "ARGV[1]", "ARGV[2]");
        assertThat(script).contains("seckill:stock:total:", "INCRBY", "> total");
    }
}
