package org.traccar.lexus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.handler.BasePositionHandler;
import org.traccar.model.Position;

import jakarta.inject.Inject;

/**
 * Runs before the filters: every decoded position reaches the notification
 * files, as in the 3.2 fork where the decoders wrote them directly.
 */
public class LexusDecodedHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexusDecodedHandler.class);

    private final LexusService service;

    @Inject
    public LexusDecodedHandler(LexusService service) {
        this.service = service;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (service.isEnabled()) {
            try {
                service.decoded(position);
            } catch (RuntimeException e) {
                LOGGER.warn("Lexus handler failed", e);
            }
        }
        callback.processed(false);
    }

}
