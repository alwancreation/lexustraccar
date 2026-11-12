package org.traccar.lexus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Notification files of the 3.2 fork. The positions decoded during one second
 * are written together in one file (alarm&lt;date&gt;S&lt;count&gt;.txt), then the PHP
 * side is told about the file, through an URL or a command line.
 *
 * One series of files per device family, as before: Teltonika (plain name),
 * Coban/GPS103 (COBAN suffix) and GT06 (gt06 suffix). The last two are
 * announced on notification.coban.url.
 */
@Singleton
public class LexusNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexusNotifier.class);

    private static final long PERIOD_MS = 1000;

    private final LexusConfig config;
    private final Map<String, Map<String, List<String>>> pending = new HashMap<>();
    private final ScheduledExecutorService timer;
    private final ExecutorService sender;

    @Inject
    public LexusNotifier(LexusConfig config) {
        this.config = config;
        if (config.isEnabled()) {
            timer = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "lexus-notifier"));
            sender = Executors.newCachedThreadPool(runnable -> daemon(runnable, "lexus-notify-send"));
            timer.scheduleAtFixedRate(this::flush, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
        } else {
            timer = null;
            sender = null;
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String suffix(String family) {
        return switch (family) {
            case LexusLegacy.FAMILY_COBAN -> "COBAN.txt";
            case LexusLegacy.FAMILY_GT06 -> "gt06.txt";
            default -> ".txt";
        };
    }

    public static boolean isNotified(String protocol) {
        return LexusLegacy.FAMILY_TELTONIKA.equals(protocol)
                || LexusLegacy.FAMILY_COBAN.equals(protocol)
                || LexusLegacy.FAMILY_GT06.equals(protocol);
    }

    /** Key of the current second; the numbers are not zero padded, as in the fork. */
    static String currentKey() {
        Calendar now = Calendar.getInstance();
        return "alarm" + now.get(Calendar.YEAR) + "-" + (now.get(Calendar.MONTH) + 1)
                + "-" + now.get(Calendar.DAY_OF_MONTH) + "-" + now.get(Calendar.HOUR_OF_DAY)
                + "-" + now.get(Calendar.MINUTE) + "-" + now.get(Calendar.SECOND);
    }

    public void add(String family, String json) {
        if (timer == null) {
            return;
        }
        String key = currentKey();
        synchronized (pending) {
            pending.computeIfAbsent(family, k -> new HashMap<>())
                    .computeIfAbsent(key, k -> new ArrayList<>())
                    .add(json);
        }
    }

    private void flush() {
        try {
            String current = currentKey();
            Map<String, Map<String, List<String>>> ready = new HashMap<>();
            synchronized (pending) {
                for (Map.Entry<String, Map<String, List<String>>> family : pending.entrySet()) {
                    Iterator<Map.Entry<String, List<String>>> iterator = family.getValue().entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<String, List<String>> second = iterator.next();
                        if (!second.getKey().equals(current)) {
                            ready.computeIfAbsent(family.getKey(), k -> new HashMap<>())
                                    .put(second.getKey(), second.getValue());
                            iterator.remove();
                        }
                    }
                }
            }
            ready.forEach((family, seconds) -> seconds.forEach((key, positions) -> write(family, key, positions)));
        } catch (RuntimeException e) {
            LOGGER.warn("Lexus notification flush failed", e);
        }
    }

    private void write(String family, String key, List<String> positions) {
        String fileName = config.getNotificationFolder() + key + "S" + positions.size() + suffix(family);
        try (PrintWriter writer = new PrintWriter(fileName, StandardCharsets.UTF_8)) {
            writer.println("[" + String.join(",", positions) + "]");
        } catch (Exception e) {
            LOGGER.warn("Lexus notification file {} failed: {}", fileName, e.getMessage());
            return;
        }
        announce(fileName, !LexusLegacy.FAMILY_TELTONIKA.equals(family));
    }

    private void announce(String fileName, boolean coban) {
        if (!config.isNotificationEnabled()) {
            return;
        }
        String argument = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
        if (config.isNotificationCommand()) {
            sender.execute(() -> runCommand(config.getNotificationCommand() + " " + argument));
        } else {
            String base = coban ? config.getNotificationCobanUrl() : config.getNotificationUrl();
            if (base == null) {
                return;
            }
            String url = base + argument;
            if (config.isNotificationAsync()) {
                sender.execute(() -> callUrl(url));
            } else {
                callUrl(url);
            }
        }
    }

    private void callUrl(String url) {
        try (InputStream stream = URI.create(url).toURL().openStream()) {
            stream.readAllBytes();
            if (config.isNotificationLog()) {
                LOGGER.info(url);
            }
        } catch (Exception e) {
            if (config.isNotificationLog()) {
                LOGGER.error("url {} {}", url, e.getMessage());
            }
        }
    }

    private void runCommand(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(command.split(" "));
            process.waitFor();
        } catch (Exception e) {
            if (config.isNotificationLog()) {
                LOGGER.error("cmd {} {}", command, e.getMessage());
            }
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

}
