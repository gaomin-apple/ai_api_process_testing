package com.labway.aft.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiImporterTest {
    @Test
    void importsOpenApiContents() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Orders", "version": "1"},
                  "paths": {
                    "/api/orders/{id}": {
                      "get": {
                        "operationId": "getOrder",
                        "tags": ["Orders"],
                        "parameters": [
                          {"name":"id","in":"path","required":true,"schema":{"type":"string"}}
                        ],
                        "responses": {"200":{"description":"ok","content":{"application/json":{"schema":{"type":"object"}}}}}
                      }
                    }
                  }
                }
                """;

        OpenApiImporter.ImportResult result = new OpenApiImporter(new ObjectMapper())
                .importContents("project-1", spec);

        assertEquals(1, result.endpoints().size());
        assertEquals("GET", result.endpoints().get(0).method());
        assertEquals("/api/orders/{id}", result.endpoints().get(0).path());
        assertNotNull(result.endpoints().get(0).responseSchema());
    }
}
