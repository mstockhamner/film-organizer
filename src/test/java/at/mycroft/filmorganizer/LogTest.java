package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class LogTest {

    @Test
    void beschreibungNenntFehlerartAuchOhneMeldung() {
        assertEquals("NullPointerException", Log.beschreibung(new NullPointerException()));
        assertEquals("IOException: kaputt", Log.beschreibung(new IOException("kaputt")));
    }
}
