package com.labway.aft.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecretRedactorTest {
    private final SecretRedactor redactor = new SecretRedactor();

    @Test
    void redactsHeadersAndBodies() {
        assertEquals("***", redactor.redactHeaders(Map.of("Authorization", "Bearer abc"))
                .get("Authorization"));
        assertEquals("{\"token\":\"***\"}", redactor.redactText("{\"token\":\"abc\"}"));
        assertEquals("username=test&password=***", redactor.redactText("username=test&password=abc"));
    }
}
