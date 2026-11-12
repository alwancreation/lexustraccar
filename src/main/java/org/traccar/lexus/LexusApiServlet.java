package org.traccar.lexus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.database.CommandsManager;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The two HTTP calls the 3.2 fork kept, with the same paths and answers:
 *
 *   GET  /api/status/get     {"success":true,"data":{"status":"UP"}}
 *   POST /api/command/send   body {"deviceId": id, "type": ..., "attributes": {"data": ...}}
 *                            {"success":true,"data":{"deviceId":..,"typeCommand":..}}
 *
 * The fork accepted commands from anybody. Set lexus.command.token to demand
 * the same value in the X-Lexus-Token header (or a "token" parameter).
 */
@Singleton
public class LexusApiServlet extends HttpServlet {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexusApiServlet.class);

    private final LexusConfig config;
    private final ObjectMapper objectMapper;
    private final Storage storage;
    private final CommandsManager commandsManager;

    @Inject
    public LexusApiServlet(
            LexusConfig config, ObjectMapper objectMapper, Storage storage, CommandsManager commandsManager) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.storage = storage;
        this.commandsManager = commandsManager;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!config.isEnabled()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST");

        String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");
        ObjectNode result = objectMapper.createObjectNode();
        try {
            switch (path) {
                case "/api/status/get" -> {
                    result.put("success", true);
                    result.putObject("data").put("status", "UP");
                }
                case "/api/command/send" -> {
                    if (!isAllowed(req)) {
                        resp.sendError(HttpServletResponse.SC_FORBIDDEN);
                        return;
                    }
                    ObjectNode data = send(objectMapper.readTree(req.getReader()));
                    result.put("success", true);
                    result.set("data", data);
                }
                default -> {
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Lexus API {} failed: {}", path, e.getMessage());
            result.removeAll();
            result.put("success", false);
            result.put("error", String.valueOf(e.getMessage()));
        }
        resp.getWriter().write(result.toString());
    }

    private boolean isAllowed(HttpServletRequest req) {
        String expected = config.getString("lexus.command.token");
        if (expected == null) {
            return true;
        }
        String given = req.getHeader("X-Lexus-Token");
        if (given == null) {
            given = req.getParameter("token");
        }
        return given != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode send(JsonNode json) throws Exception {
        String legacyId = json.path("deviceId").asText("");
        String type = json.path("type").asText("");
        if (legacyId.isEmpty() || type.isEmpty()) {
            throw new IllegalArgumentException("deviceId and type are required");
        }

        String prefix = config.getDeviceIdPrefix();
        String uniqueId = !prefix.isEmpty() && legacyId.startsWith(prefix)
                ? legacyId.substring(prefix.length()) : legacyId;
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("uniqueId", uniqueId)));
        if (device == null) {
            throw new IllegalArgumentException("Unknown device " + legacyId);
        }

        Command command = new Command();
        command.setDeviceId(device.getId());
        command.setType(type);
        JsonNode attributes = json.path("attributes");
        if (attributes.isObject()) {
            for (var entry : attributes.properties()) {
                JsonNode value = entry.getValue();
                if (value.isBoolean()) {
                    command.set(entry.getKey(), value.asBoolean());
                } else if (value.isIntegralNumber()) {
                    command.set(entry.getKey(), value.asLong());
                } else if (value.isNumber()) {
                    command.set(entry.getKey(), value.asDouble());
                } else if (!value.isNull()) {
                    command.set(entry.getKey(), value.asText());
                }
            }
        }
        // as before: the command goes out now or the call fails, nothing is queued
        command.set(Command.KEY_NO_QUEUE, true);
        commandsManager.sendCommand(command);

        ObjectNode data = objectMapper.createObjectNode();
        if (json.path("deviceId").isNumber()) {
            data.put("deviceId", json.path("deviceId").asLong());
        } else {
            data.put("deviceId", legacyId);
        }
        data.put("typeCommand", type);
        return data;
    }

}
