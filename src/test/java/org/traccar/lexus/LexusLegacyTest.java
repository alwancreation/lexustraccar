package org.traccar.lexus;

import org.junit.jupiter.api.Test;
import org.traccar.model.Position;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LexusLegacyTest {

    private Position position(String protocol) {
        Position position = new Position(protocol);
        position.setTime(new Date(1000));
        position.setValid(true);
        position.setLatitude(31.5);
        position.setLongitude(-8.0);
        position.setCourse(90);
        return position;
    }

    @Test
    public void testTeltonika() {
        Position position = position("teltonika");
        position.set(Position.KEY_POWER, 12.65);
        position.set("io66", 12650L);
        position.set("io239", 1L);
        position.set("io21", 4L);

        var view = LexusLegacy.build("1", "123", position, 0);

        assertEquals("1123", view.device());
        assertEquals(1, view.attributes().get("power"));
        assertEquals(4, view.attributes().get("gsm"));
        assertEquals(0, view.attributes().get("hamza9"));
        assertEquals("-", view.statusIgnition());
        assertTrue(view.json().startsWith("{ \"Device\": \"1123\", \"Timestamp\": \"1000\", \"valid\": \"true\""));
        assertTrue(view.json().contains("\"Heading_desc\": \"E\""));
        // the Traccar position keeps its own meaning of "power"
        assertEquals(12.65, position.getDouble(Position.KEY_POWER));
    }

    @Test
    public void testCoban() {
        Position position = position("gps103");
        position.set(Position.KEY_IGNITION, true);

        var view = LexusLegacy.build("", "123", position, 57);

        assertEquals("acc on", view.statusIgnition());
        assertEquals("acc on", view.attributes().get(Position.KEY_ALARM));
        assertEquals(1, view.attributes().get("power"));
        assertEquals(57, view.attributes().get("io199"));
    }

    @Test
    public void testGt06() {
        Position position = position("gt06");
        position.set(Position.KEY_IGNITION, false);
        position.set(Position.KEY_RSSI, 3);

        var view = LexusLegacy.build("", "123", position, 0);
        assertEquals("acc off", view.statusIgnition());
        assertEquals(0, view.attributes().get("power"));
        assertEquals(3, view.attributes().get("io27"));

        position.addAlarm(Position.ALARM_SOS);
        assertEquals("ac alarm", LexusLegacy.build("", "123", position, 0).statusIgnition());
    }

    @Test
    public void testQuotesAreEscaped() {
        Position position = position("teltonika");
        position.set("note", "a \"b\"");
        assertFalse(LexusLegacy.build("", "1", position, 0).json().contains("a \"b\""));
    }

}
