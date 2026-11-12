/*
 * Lexus compatibility layer: keeps the behaviour of the customised
 * Traccar 3.2 fork (OpenGTS database, notification files, command and
 * socket endpoints) on top of an unmodified modern Traccar core.
 */
package org.traccar.lexus;

import org.traccar.config.Config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Settings of the compatibility layer. The notification keys keep the names
 * they had in the 3.2 fork so the old traccar.xml entries can be pasted as is.
 */
@Singleton
public class LexusConfig {

    private final Config config;

    @Inject
    public LexusConfig(Config config) {
        this.config = config;
    }

    public String getString(String key) {
        String value = config.getString(key);
        return value != null && !value.trim().isEmpty() ? value.trim() : null;
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getString(key));
    }

    public int getInteger(String key, int defaultValue) {
        String value = getString(key);
        try {
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Master switch: nothing of the layer runs unless lexus.enable is true. */
    public boolean isEnabled() {
        return getBoolean("lexus.enable");
    }

    /**
     * Prefix put in front of the IMEI to build the legacy device id. The old
     * configuration used either the bare IMEI or CONCAT('1', imeiNumber).
     */
    public String getDeviceIdPrefix() {
        String prefix = getString("lexus.deviceIdPrefix");
        return prefix != null ? prefix : "";
    }

    public boolean isNotificationEnabled() {
        return isEnabled() && getBoolean("notification.enable");
    }

    public boolean isNotificationAsync() {
        return getBoolean("notification.async");
    }

    public boolean isNotificationLog() {
        return getBoolean("notification.log");
    }

    public boolean isNotificationCommand() {
        return getBoolean("notification.enableCMD");
    }

    public String getNotificationCommand() {
        return getString("notification.CMD");
    }

    public String getNotificationUrl() {
        return getString("notification.url");
    }

    public String getNotificationCobanUrl() {
        return getString("notification.coban.url");
    }

    public String getNotificationFolder() {
        String folder = getString("notification-folder");
        return folder != null ? folder : "";
    }

}
