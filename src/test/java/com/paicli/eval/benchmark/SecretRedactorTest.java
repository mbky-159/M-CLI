package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactorTest {

    @Test
    void redactsBearerKeysJwtAndBase64Payloads() {
        String bearer = "bearer-token-value-123456";
        String apiKey = "deepseek-secret-value-123456";
        String jsonKey = "json-secret-value-123456";
        String openAiStyle = "sk-abcdefghijklmnopqrstuvwxyz123456";
        String jwt = "eyJabcdefghijk.abcdefghijklmnop.abcdefghijklmnop";
        String image = "A".repeat(160);
        String input = """
                Authorization: Bearer %s
                DEEPSEEK_API_KEY=%s
                {"apiKey":"%s","imageBase64":"%s"}
                data:image/png;base64,%s
                standalone=%s
                jwt=%s
                inputTokens=42
                """.formatted(bearer, apiKey, jsonKey, image, image, openAiStyle, jwt);

        String redacted = SecretRedactor.redact(input);

        assertFalse(redacted.contains(bearer));
        assertFalse(redacted.contains(apiKey));
        assertFalse(redacted.contains(jsonKey));
        assertFalse(redacted.contains(image));
        assertFalse(redacted.contains(openAiStyle));
        assertFalse(redacted.contains(jwt));
        assertTrue(redacted.contains("Bearer " + SecretRedactor.REDACTED));
        assertTrue(redacted.contains("DEEPSEEK_API_KEY=" + SecretRedactor.REDACTED));
        assertTrue(redacted.contains("inputTokens=42"));
    }

    @Test
    void keepsOrdinaryTextAndNullStable() {
        assertEquals("model=deepseek-v4-flash tokens=128",
                SecretRedactor.redact("model=deepseek-v4-flash tokens=128"));
        assertEquals("", SecretRedactor.redact(""));
        assertNull(SecretRedactor.redact(null));
    }
}
