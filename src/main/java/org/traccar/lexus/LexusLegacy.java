package org.traccar.lexus;

import org.traccar.model.Position;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rebuilds a position the way the customised 3.2 fork exposed it: same
 * attribute names (io ids, power, statusignition, io199...) and same JSON
 * text in the notification files. The Traccar position itself is not touched.
 */
public final class LexusLegacy {

    public static final String FAMILY_TELTONIKA = "teltonika";
    public static final String FAMILY_COBAN = "gps103";
    public static final String FAMILY_GT06 = "gt06";

    private static final String ACC_ON = "acc on";
    private static final String ACC_OFF = "acc off";

    private LexusLegacy() {
    }

    /** A position as the old system saw it. */
    public record View(
            String device, String uniqueId, Position position,
            Map<String, Object> attributes, String statusIgnition, String json) {
    }

    private static Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        return value != null ? Boolean.parseBoolean(value.toString()) : null;
    }

    private static void teltonika(Map<String, Object> attributes) {
        // io21: GSM level
        Number gsm = number(attributes.get("io21"));
        if (gsm != null) {
            attributes.put("gsm", gsm.intValue());
        }
        // io66: external voltage, raw millivolts plus volts in batteryio
        Number external = number(attributes.get("io66"));
        if (external != null) {
            attributes.put("batteryio", external.intValue() * 0.001);
        }
        // io67: internal battery in volts
        Number battery = number(attributes.get("io67"));
        if (battery != null) {
            double volts = battery.intValue() * 0.001;
            attributes.put("io67", volts);
            attributes.put("battery", volts);
        }
        // "power" carried the ignition input (io239), not a voltage
        attributes.remove("power");
        Number ignition = number(attributes.get("io239"));
        if (ignition != null) {
            attributes.put("power", ignition.intValue());
        }
        // EYE sensors were copied onto the ids of the BLE temperature inputs
        Number eye1 = number(attributes.get("io10800"));
        if (eye1 != null) {
            attributes.put("io25", eye1.intValue());
        }
        Number eye2 = number(attributes.get("io10801"));
        if (eye2 != null) {
            attributes.put("io26", eye2.intValue());
        }
        attributes.put("hamza9", 0);
    }

    private static String coban(Map<String, Object> attributes, int distance) {
        String status;
        Boolean ignition = bool(attributes.get(Position.KEY_IGNITION));
        if (ignition != null) {
            status = ignition ? ACC_ON : ACC_OFF;
            attributes.put("io1", ignition ? "1" : "0");
            attributes.put("power", ignition ? 1 : 0);
            attributes.put(Position.KEY_ALARM, status);
        } else if (attributes.get(Position.KEY_ALARM) != null) {
            status = attributes.get(Position.KEY_ALARM).toString();
        } else if (attributes.get(Position.KEY_EVENT) != null) {
            status = attributes.get(Position.KEY_EVENT).toString();
            attributes.put(Position.KEY_ALARM, status);
        } else {
            status = "tracker";
            attributes.put(Position.KEY_ALARM, status);
        }
        attributes.put("io199", distance);
        return status;
    }

    private static String gt06(Map<String, Object> attributes, int distance) {
        String status = "-";
        Boolean ignition = bool(attributes.get(Position.KEY_IGNITION));
        if (ignition != null) {
            attributes.put("valueignition", ignition.toString());
            attributes.put("power", ignition ? 1 : 0);
            status = ignition ? ACC_ON : ACC_OFF;
            Object alarm = attributes.get(Position.KEY_ALARM);
            if (alarm != null) {
                attributes.put("alarmType", alarm.toString());
                status = "ac alarm";
            }
        }
        Number level = number(attributes.get(Position.KEY_BATTERY_LEVEL));
        if (level != null) {
            // the fork published the raw 0..6 battery step of the status packet
            int raw = (int) Math.round(level.doubleValue() * 6 / 100);
            attributes.put("io67", raw);
            attributes.put("battery", raw);
        }
        Number rssi = number(attributes.get(Position.KEY_RSSI));
        if (rssi != null) {
            attributes.put("gsm", rssi.intValue());
            attributes.put("io27", rssi.intValue());
        }
        attributes.put("io199", distance);
        return status;
    }

    /**
     * @param distance metres from the previous point of the device, for the
     *                 protocols that publish it as io199 (0 otherwise)
     */
    public static View build(String prefix, String uniqueId, Position position, int distance) {
        Map<String, Object> attributes = new LinkedHashMap<>(position.getAttributes());
        String status = "-";
        String protocol = position.getProtocol() != null ? position.getProtocol() : "";

        switch (protocol) {
            case FAMILY_TELTONIKA -> teltonika(attributes);
            case FAMILY_COBAN -> status = coban(attributes, distance);
            case FAMILY_GT06 -> status = gt06(attributes, distance);
            case "ruptela" -> {
                Number trip = number(attributes.get("io8"));
                if (trip != null) {
                    attributes.put("io199", trip.longValue());
                }
            }
            case "wialon" -> {
                Object driver = attributes.get(Position.KEY_DRIVER_UNIQUE_ID);
                if (driver != null) {
                    attributes.put("uniqdriver", driver);
                }
            }
            default -> {
            }
        }

        String device = prefix + uniqueId;
        return new View(device, uniqueId, position, attributes, status, toJson(device, position, attributes, status));
    }

    public static boolean usesDistance(String protocol) {
        return FAMILY_COBAN.equals(protocol) || FAMILY_GT06.equals(protocol);
    }

    public static String headingString(double heading) {
        if (!Double.isNaN(heading) && heading >= 0.0) {
            return switch ((int) Math.round(heading / 45.0) % 8) {
                case 1 -> "NE";
                case 2 -> "E";
                case 3 -> "SE";
                case 4 -> "S";
                case 5 -> "SW";
                case 6 -> "W";
                case 7 -> "NW";
                default -> "N";
            };
        }
        return "";
    }

    private static String escape(Object value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
    }

    /** Text of one position in a notification file; every value is a string, as before. */
    static String toJson(String device, Position position, Map<String, Object> attributes, String status) {
        StringBuilder raw = new StringBuilder("\"rawData\": {");
        boolean first = true;
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (!first) {
                raw.append(',');
            }
            raw.append('"').append(escape(entry.getKey())).append("\": \"")
                    .append(escape(entry.getValue())).append('"');
            first = false;
        }
        raw.append('}');

        long timestamp = position.getDeviceTime() != null ? position.getDeviceTime().getTime() : 0;
        return "{"
                + " \"Device\": \"" + escape(device) + "\""
                + ", \"Timestamp\": \"" + timestamp + "\""
                + ", \"valid\": \"" + position.getValid() + "\""
                + ", \"GPSPoint_lat\": \"" + position.getLatitude() + "\""
                + ", \"GPSPoint_lon\": \"" + position.getLongitude() + "\""
                + ", \"Altitude\": \"" + position.getAltitude() + "\""
                + ", \"Speed\": \"" + position.getSpeed() + "\""
                + ", \"Heading\": \"" + position.getCourse() + "\""
                + ", \"statusignition\": \"" + escape(status) + "\""
                + ", \"Heading_desc\": \"" + headingString(position.getCourse()) + "\""
                + ", \"Address\": \"" + escape(position.getAddress()) + "\""
                + ", \"Type\": \"" + escape(attributes.get(Position.KEY_TYPE)) + "\""
                + "," + raw
                + "}";
    }

    private static String format(Object value) {
        if (value instanceof Double || value instanceof Float) {
            return new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(value);
        }
        return String.valueOf(value);
    }

    /** Old MiscFormatter.toXmlString: &lt;info&gt;&lt;key&gt;value&lt;/key&gt;...&lt;/info&gt;. */
    public static String toXml(Map<String, Object> attributes) {
        StringBuilder result = new StringBuilder("<info>");
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            result.append('<').append(entry.getKey()).append('>');
            result.append(format(entry.getValue()));
            result.append("</").append(entry.getKey()).append('>');
        }
        return result.append("</info>").toString();
    }

}
