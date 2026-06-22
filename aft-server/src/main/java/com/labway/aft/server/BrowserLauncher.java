package com.labway.aft.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "aft.browser.open", havingValue = "true", matchIfMissing = true)
public class BrowserLauncher {
    private final ServletWebServerApplicationContext context;

    public BrowserLauncher(ServletWebServerApplicationContext context) {
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser() {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(new URI("http://127.0.0.1:" + context.getWebServer().getPort()));
        } catch (Exception ignored) {
            // Browser launch is a convenience; the service remains usable if the OS rejects it.
        }
    }
}
