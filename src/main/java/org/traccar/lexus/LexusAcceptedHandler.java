package org.traccar.lexus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.handler.BasePositionHandler;
import org.traccar.model.Position;

import jakarta.inject.Inject;

/**
 * Runs after the filters, next to Traccar's own database handler: the position
 * is inserted in OpenGTS and pushed to the live sockets.
 */
public class LexusAcceptedHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexusAcceptedHandler.class);

    private final LexusService service;

    @Inject
    public LexusAcceptedHandler(LexusService service) {
        this.service = service;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (service.isEnabled()) {
            try {
                service.accepted(position);
            } catch (RuntimeException e) {
                LOGGER.warn("Lexus handler failed", e);
            }
        }
        callback.processed(false);
    }

}
