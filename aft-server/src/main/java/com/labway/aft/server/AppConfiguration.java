package com.labway.aft.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labway.aft.engine.FlowEngine;
import com.labway.aft.engine.FlowValidator;
import com.labway.aft.openapi.OpenApiImporter;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class AppConfiguration {
    @Bean
    OpenApiImporter openApiImporter(ObjectMapper objectMapper) {
        return new OpenApiImporter(objectMapper);
    }

    @Bean
    FlowValidator flowValidator() {
        return new FlowValidator();
    }

    @Bean
    FlowEngine flowEngine(ObjectMapper objectMapper) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))
                .followRedirects(false)
                .build();
        return new FlowEngine(client, objectMapper);
    }

    @Bean
    WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                        .allowedMethods("GET", "POST", "PUT", "DELETE");
            }
        };
    }
}
