package com.labway.aft.server;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class SqliteDataSourceConfiguration {
    @Bean
    DataSource dataSource(DataSourceProperties properties) throws IOException {
        String url = properties.determineUrl();
        if (url.startsWith("jdbc:sqlite:")) {
            String location = url.substring("jdbc:sqlite:".length());
            if (!location.startsWith(":memory:") && !location.startsWith("file:")) {
                Path database = Path.of(location).toAbsolutePath();
                if (database.getParent() != null) {
                    Files.createDirectories(database.getParent());
                }
            }
        }
        return properties.initializeDataSourceBuilder().build();
    }
}
