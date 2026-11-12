package org.traccar.lexus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Position;

import java.nio.channels.ClosedChannelException;
import java.util.Map;

/**
 * Live positions of one vehicle for the mobile application.
 *
 * Text frames, JSON:
 *   client -> {"a": account, "u": user, "p": password, "d": deviceID}   first frame only
 *   server -> {"type": "ready"}                                          subscription accepted
 *   server -> {"type": "position", "d", "lat", "lon", "speed", ...}      one per position
 *   server -> {"type": "error", "message": ...} then close               refused
 *
 * The credentials travel in the first frame rather than in the URL so they
 * do not end up in access logs.
 */
public class LexusSocket implements Session.Listener.AutoDemanding, LexusService.Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexusSocket.class);

    public static final int CLOSE_BAD_REQUEST = 4000;
    public static final int CLOSE_UNAUTHORIZED = 4001;

    private static final double KNOTS_TO_KPH = 1.852;

    private final ObjectMapper objectMapper;
    private final LexusService service;

    private volatile Session session;
    private volatile String uniqueId;
    private String externalId;

    public LexusSocket(ObjectMapper objectMapper, LexusService service) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @Override
    public void onWebSocketOpen(Session session) {
        this.session = session;
    }

    @Override
    public void onWebSocketText(String message) {
        if (uniqueId != null) {
            return;
        }
        try {
            JsonNode json = objectMapper.readTree(message);
            String device = json.path("d").asText("");
            String found = service.authorize(
                    json.path("a").asText(""), json.path("u").asText(""), json.path("p").asText(""), device);
            if (found == null) {
                refuse(CLOSE_UNAUTHORIZED, "unauthorized");
                return;
            }

            externalId = device;
            uniqueId = found;
            service.addListener(found, this);

            send(objectMapper.createObjectNode().put("type", "ready"));

            LexusLegacy.View last = service.getLast(found);
            if (last != null) {
                onPosition(last);
            }
        } catch (Exception e) {
            LOGGER.warn("Lexus socket request failed", e);
            refuse(CLOSE_BAD_REQUEST, "bad request");
        }
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        unsubscribe();
        session = null;
        callback.succeed();
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        unsubscribe();
        if (!(cause instanceof ClosedChannelException)) {
            LOGGER.warn("Lexus socket error: {}", cause.getMessage());
        }
    }

    private void unsubscribe() {
        String subscribed = uniqueId;
        if (subscribed != null) {
            service.removeListener(subscribed, this);
        }
    }

    @Override
    public void onPosition(LexusLegacy.View view) {
        Position position = view.position();
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "position");
        json.put("d", externalId);
        json.put("lat", position.getLatitude());
        json.put("lon", position.getLongitude());
        json.put("speed", position.getSpeed() * KNOTS_TO_KPH);
        json.put("course", position.getCourse());
        json.put("valid", position.getValid());
        if (position.getFixTime() != null) {
            json.put("time", position.getFixTime().getTime() / 1000);
        }
        json.put("statusignition", view.statusIgnition());

        Object ignition = view.attributes().get(Position.KEY_IGNITION);
        if (ignition != null) {
            json.put("ignition", String.valueOf(ignition));
        }
        ObjectNode other = json.putObject("attributes");
        for (Map.Entry<String, Object> entry : view.attributes().entrySet()) {
            if (entry.getValue() != null) {
                other.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        send(json);
    }

    private void send(ObjectNode json) {
        Session current = session;
        if (current != null && current.isOpen()) {
            current.sendText(json.toString(), Callback.NOOP);
        }
    }

    private void refuse(int code, String message) {
        send(objectMapper.createObjectNode().put("type", "error").put("message", message));
        Session current = session;
        if (current != null) {
            current.close(code, message, Callback.NOOP);
        }
    }

}
