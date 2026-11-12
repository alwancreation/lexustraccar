package org.traccar.lexus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;

/**
 * /api/lexus/socket. Traccar's own web socket stays on /api/socket (it needs a
 * Traccar session); this one is opened with OpenGTS credentials.
 */
@Singleton
public class LexusSocketServlet extends JettyWebSocketServlet {

    private static final long IDLE_TIMEOUT = 60000;
    private static final int MAX_MESSAGE_SIZE = 4096;

    private final ObjectMapper objectMapper;
    private final LexusService service;
    private final LexusConfig config;

    @Inject
    public LexusSocketServlet(ObjectMapper objectMapper, LexusService service, LexusConfig config) {
        this.objectMapper = objectMapper;
        this.service = service;
        this.config = config;
    }

    @Override
    public void configure(JettyWebSocketServletFactory factory) {
        factory.setIdleTimeout(Duration.ofMillis(IDLE_TIMEOUT));
        factory.setMaxTextMessageSize(MAX_MESSAGE_SIZE);
        factory.setCreator((req, resp) -> {
            if (!config.isEnabled() || !config.getBoolean("lexus.socket.enable")) {
                return null;
            }
            return new LexusSocket(objectMapper, service);
        });
    }

}
