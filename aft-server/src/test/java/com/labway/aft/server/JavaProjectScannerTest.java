package com.labway.aft.server;

import com.labway.aft.domain.EndpointDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaProjectScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void scansSpringMappingsAndBuildsEndpointDefinitions() throws Exception {
        Path controller = tempDir.resolve("src/main/java/com/example/OrderController.java");
        Files.createDirectories(controller.getParent());
        Files.writeString(controller, """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/orders")
                class OrderController {
                    private final OrderService orderService;

                    OrderController(OrderService orderService) {
                        this.orderService = orderService;
                    }

                    @PostMapping
                    OrderDto create() {
                        return orderService.create();
                    }

                    @GetMapping("/{id}")
                    OrderDto get(@PathVariable String id) {
                        return orderService.get(id);
                    }
                }
                """);

        JavaProjectScanner scanner = new JavaProjectScanner();
        JavaProjectScanner.ScanResult scan = scanner.scan(tempDir.toString());
        List<EndpointDefinition> endpoints = scanner.endpointDefinitions("project-1", scan);

        assertEquals(1, scan.includedFiles());
        assertEquals(2, scan.discoveredEndpoints().size());
        assertTrue(scan.files().get(0).calls().contains("orderService.create"));
        assertTrue(scan.files().get(0).calls().contains("orderService.get"));

        EndpointDefinition create = endpoints.stream()
                .filter(endpoint -> endpoint.method().equals("POST"))
                .findFirst()
                .orElseThrow();
        assertEquals("/orders", create.path());
        assertEquals("create", create.operationId());

        EndpointDefinition get = endpoints.stream()
                .filter(endpoint -> endpoint.method().equals("GET"))
                .findFirst()
                .orElseThrow();
        assertEquals("/orders/{id}", get.path());
        assertEquals(1, get.parameters().size());
        assertEquals("id", get.parameters().get(0).name());
        assertEquals("path", get.parameters().get(0).location());
    }
}
