package org.traccar.lexus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.helper.DistanceCalculator;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Heart of the compatibility layer. Every decoded position goes through
 * {@link #decoded} (notification files); the ones Traccar keeps after its
 * filters go through {@link #accepted} (OpenGTS insert and live sockets).
 */
@Singleton
public class LexusService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexusService.class);

    public static final String QUERY_INSERT_POSITION = "lexus.insertPosition";
    public static final String QUERY_SOCKET_DEVICE = "lexus.selectSocketDevice";

    /** Receives the positions of one device, in legacy form. */
    public interface Listener {
        void onPosition(LexusLegacy.View view);
    }

    private final LexusConfig config;
    private final LexusDatabase database;
    private final LexusNotifier notifier;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;

    private final Map<Position, LexusLegacy.View> views = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Long, double[]> lastPoints = new ConcurrentHashMap<>();
    private final Map<String, LexusLegacy.View> lastViews = new ConcurrentHashMap<>();
    private final Map<String, Set<Listener>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService[] writers;

    @Inject
    public LexusService(
            LexusConfig config, LexusDatabase database, LexusNotifier notifier,
            CacheManager cacheManager, ObjectMapper objectMapper) {
        this.config = config;
        this.database = database;
        this.notifier = notifier;
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper;
        // one queue per group of devices: positions of a device stay in order
        // and a slow OpenGTS database never blocks the protocol threads
        writers = new ExecutorService[Math.max(1, config.getInteger("lexus.database.threads", 4))];
        for (int i = 0; i < writers.length; i++) {
            String name = "lexus-db-" + i;
            writers[i] = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    private int distance(Position position) {
        String protocol = position.getProtocol();
        if (!LexusLegacy.usesDistance(protocol) || position.getOutdated()) {
            return 0;
        }
        double[] previous = lastPoints.put(
                position.getDeviceId(), new double[] {position.getLatitude(), position.getLongitude()});
        if (previous == null) {
            return 0;
        }
        if (LexusLegacy.FAMILY_GT06.equals(protocol)
                && !Boolean.TRUE.equals(position.getAttributes().get(Position.KEY_IGNITION))) {
            return 0; // GT06: distance only counted with the ignition on
        }
        return (int) DistanceCalculator.distance(
                previous[0], previous[1], position.getLatitude(), position.getLongitude());
    }

    private LexusLegacy.View view(Position position) {
        LexusLegacy.View view = views.get(position);
        if (view == null) {
            Device device = cacheManager.getObject(Device.class, position.getDeviceId());
            if (device == null) {
                return null;
            }
            view = LexusLegacy.build(
                    config.getDeviceIdPrefix(), device.getUniqueId(), position, distance(position));
            views.put(position, view);
        }
        return view;
    }

    /** A command answer is not a position: the fork never published them. */
    private static boolean isCommandResult(Position position) {
        return LexusLegacy.FAMILY_TELTONIKA.equals(position.getProtocol())
                && position.hasAttribute(Position.KEY_RESULT);
    }

    public void decoded(Position position) {
        if (isCommandResult(position)) {
            return;
        }
        LexusLegacy.View view = view(position);
        if (view != null && LexusNotifier.isNotified(position.getProtocol())) {
            notifier.add(position.getProtocol(), view.json());
        }
    }

    public void accepted(Position position) {
        if (isCommandResult(position)) {
            return;
        }
        LexusLegacy.View view = view(position);
        if (view == null) {
            return;
        }
        views.remove(position);

        if (database.hasQuery(QUERY_INSERT_POSITION)) {
            int index = (int) Math.floorMod(position.getDeviceId(), (long) writers.length);
            writers[index].execute(() -> insert(view));
        }

        lastViews.put(view.uniqueId(), view);
        Set<Listener> deviceListeners = listeners.get(view.uniqueId());
        if (deviceListeners != null) {
            for (Listener listener : deviceListeners) {
                try {
                    listener.onPosition(view);
                } catch (RuntimeException e) {
                    LOGGER.warn("Lexus socket update failed", e);
                }
            }
        }
    }

    private static Object legacyId(String device) {
        try {
            return Long.parseLong(device);
        } catch (NumberFormatException e) {
            return device;
        }
    }

    private void insert(LexusLegacy.View view) {
        try {
            Position position = view.position();
            String json = objectMapper.writeValueAsString(view.attributes());
            String xml = LexusLegacy.toXml(view.attributes());

            Map<String, Object> values = LexusDatabase.parameters();
            values.put("id", 0L);
            values.put("deviceid", legacyId(view.device()));
            values.put("device_id", legacyId(view.device()));
            values.put("uniqueid", view.uniqueId());
            values.put("protocol", position.getProtocol());
            values.put("servertime", position.getServerTime());
            values.put("devicetime", position.getDeviceTime());
            values.put("fixtime", position.getFixTime());
            values.put("time", position.getFixTime());
            values.put("valid", position.getValid());
            values.put("latitude", position.getLatitude());
            values.put("longitude", position.getLongitude());
            values.put("altitude", position.getAltitude());
            values.put("speed", position.getSpeed()); // knots, as before
            values.put("course", position.getCourse());
            values.put("address", position.getAddress());
            values.put("statusignition", view.statusIgnition());
            values.put("attributes", config.getBoolean("lexus.database.xml") ? xml : json);
            values.put("extended_info", xml);
            values.put("other", xml);
            values.put("power", 0L);

            database.update(QUERY_INSERT_POSITION, values);
        } catch (Exception e) {
            LOGGER.warn("Lexus position insert failed for {}: {}", view.device(), e.getMessage());
        }
    }

    /**
     * Checks OpenGTS credentials for one vehicle with lexus.selectSocketDevice.
     *
     * @return the IMEI of the vehicle, or null when access is refused
     */
    public String authorize(String account, String user, String password, String device) throws Exception {
        if (!database.hasQuery(QUERY_SOCKET_DEVICE)
                || account.isEmpty() || password.isEmpty() || device.isEmpty()) {
            return null;
        }
        Map<String, Object> values = LexusDatabase.parameters();
        values.put("account", account);
        values.put("user", user);
        values.put("password", password);
        values.put("device", device);
        Map<String, Object> row = database.selectFirst(QUERY_SOCKET_DEVICE, values);
        if (row == null) {
            return null;
        }
        Object uniqueId = row.get("uniqueid");
        return uniqueId != null && !uniqueId.toString().isEmpty() ? uniqueId.toString() : null;
    }

    public void addListener(String uniqueId, Listener listener) {
        listeners.computeIfAbsent(uniqueId, k -> ConcurrentHashMap.newKeySet()).add(listener);
    }

    public void removeListener(String uniqueId, Listener listener) {
        Set<Listener> deviceListeners = listeners.get(uniqueId);
        if (deviceListeners != null) {
            deviceListeners.remove(listener);
        }
    }

    public LexusLegacy.View getLast(String uniqueId) {
        return lastViews.get(uniqueId);
    }

}
