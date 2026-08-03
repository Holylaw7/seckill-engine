package com.seckill.integration.load;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 压测用例元注解：默认跳过，仅当 -Dload.enabled=true 时执行。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@EnabledIfSystemProperty(named = LoadConstants.LOAD_ENABLED, matches = "true")
public @interface LoadTest {
}
