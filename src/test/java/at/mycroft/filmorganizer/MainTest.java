package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void begruessungNenntDasProgramm() {
        assertEquals("Filmorganizer", Main.begruessung());
    }
}
