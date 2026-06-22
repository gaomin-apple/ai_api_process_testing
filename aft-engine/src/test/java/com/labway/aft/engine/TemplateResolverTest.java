package com.labway.aft.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateResolverTest {
    private final TemplateResolver resolver = new TemplateResolver();

    @Test
    void resolvesMultipleVariables() {
        assertEquals(
                "/orders/42?token=abc",
                resolver.resolve("/orders/${run.id}?token=${run.token}", Map.of(
                        "run.id", "42",
                        "run.token", "abc"
                ))
        );
    }

    @Test
    void rejectsMissingVariables() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("${run.missing}", Map.of()));
    }

    @Test
    void resolvesUnicodeVariableNames() {
        assertEquals(
                "Bearer secret-token",
                resolver.resolve("Bearer ${run.认证token}", Map.of("run.认证token", "secret-token"))
        );
    }

    @Test
    void listsAvailableRunVariablesWhenReferenceIsMissing() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve("${run.token}", Map.of("run.认证token", "secret-token"))
        );
        assertEquals(
                "Missing variable: run.token. Available run variables: run.认证token",
                error.getMessage()
        );
    }
}
