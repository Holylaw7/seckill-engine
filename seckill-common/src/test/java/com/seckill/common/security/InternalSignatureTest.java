package com.seckill.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("unit")
class InternalSignatureTest {

    @Test
    void differentNonceShouldProduceDifferentSignatureForSameTimestamp() {
        String first = InternalSignature.sign("secret", "order-service", "1000", "nonce-1");
        String second = InternalSignature.sign("secret", "order-service", "1000", "nonce-2");

        assertNotEquals(first, second);
        assertTrue(InternalSignature.verify("secret", "order-service", "1000", "nonce-1", first));
        assertTrue(InternalSignature.verify("secret", "order-service", "1000", "nonce-2", second));
    }
}
