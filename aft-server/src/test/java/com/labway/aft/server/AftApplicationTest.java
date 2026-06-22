package com.labway.aft.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "aft.browser.open=false",
        "spring.datasource.url=jdbc:h2:mem:aft;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false"
})
class AftApplicationTest {
    @Test
    void contextLoads() {
    }
}
