package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void hilfeNenntAlleBefehle() {
        String hilfe = Main.hilfe();
        assertTrue(hilfe.contains("vorschau"));
        assertTrue(hilfe.contains("ausfuehren"));
        assertTrue(hilfe.contains("rueckgaengig"));
    }
}
