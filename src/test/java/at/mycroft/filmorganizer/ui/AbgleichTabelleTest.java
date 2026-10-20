package at.mycroft.filmorganizer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Abgleich;
import at.mycroft.filmorganizer.Abgleich.Wahl;
import at.mycroft.filmorganizer.Ausfuehrung;
import at.mycroft.filmorganizer.Main;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tabelle des Abgleichs: Vorschläge, Entscheidungen für mehrere Zeilen, Speichern und Wiederherstellen. */
class AbgleichTabelleTest {

    @TempDir
    Path basis;

    Path a;
    Path b;

    @BeforeEach
    void sammlungen() throws IOException {
        a = basis.resolve("A");
        b = basis.resolve("B");
        einsortieren(a, "Der.Pate.1972.German.1080p/pate.mkv", "pate 1080p", "Alien.1979.German.720p/alien.mkv", "alien a");
        einsortieren(b, "Der.Pate.1972.German.720p/pate.mkv", "pate 720p", "Alien.1979.German.720p/alien.mkv", "alien b");
    }

    @Test
    void zeigtFundeMitVorschlagUndUebernimmtEntscheidungen() throws IOException {
        AbgleichTabelle tabelle = new AbgleichTabelle();
        int[] aenderungen = {0};
        tabelle.beiAenderung(() -> aenderungen[0]++);
        tabelle.anzeigen(Abgleich.abgleichen(a, b, false), Map.of());

        int pate = zeile(tabelle, "Der Pate (1972)");
        int alien = zeile(tabelle, "Alien (1979)");
        assertEquals("Doublette", tabelle.getValueAt(pate, AbgleichTabelle.STATUS));
        assertEquals("1 behalten (Vorschlag)", tabelle.getValueAt(pate, AbgleichTabelle.ENTSCHEIDUNG));
        assertTrue(String.valueOf(tabelle.getValueAt(pate, AbgleichTabelle.ZWEITE)).startsWith("B: Der Pate (1972)"));
        assertEquals("bitte wählen", tabelle.getValueAt(alien, AbgleichTabelle.ENTSCHEIDUNG));
        assertEquals(List.of("bitte wählen", "1 behalten", "2 behalten", "Beide behalten"),
                List.of(tabelle.auswahl(alien)));
        assertEquals(1, tabelle.abgaenge("B"));
        assertEquals(0, tabelle.abgaenge("A"));

        tabelle.wahlSetzen(new int[] {pate, alien}, Wahl.ZWEITE_BEHALTEN);
        assertEquals(1, aenderungen[0]);
        assertEquals(2, tabelle.abgaenge("A"));
        assertEquals(0, tabelle.abgaenge("B"));

        // Speichern und beim nächsten Abgleich wiederherstellen.
        Path speicher = basis.resolve("arbeitsstaende");
        Abgleich.wahlenSpeichern(speicher, a, b, tabelle.wahlen());
        AbgleichTabelle spaeter = new AbgleichTabelle();
        spaeter.anzeigen(Abgleich.abgleichen(a, b, false), Abgleich.wahlenLaden(speicher, a, b));
        assertEquals("2 behalten", spaeter.getValueAt(zeile(spaeter, "Alien (1979)"), AbgleichTabelle.ENTSCHEIDUNG));
        assertEquals(2, spaeter.abgaenge("A"));

        // Zurück zum Vorschlag: keine gespeicherte Abweichung mehr.
        spaeter.setValueAt("1 behalten (Vorschlag)", zeile(spaeter, "Der Pate (1972)"), AbgleichTabelle.ENTSCHEIDUNG);
        assertEquals(1, spaeter.wahlen().size());
    }

    private static int zeile(AbgleichTabelle tabelle, String titel) {
        for (int i = 0; i < tabelle.getRowCount(); i++) {
            if (tabelle.getValueAt(i, AbgleichTabelle.TITEL).equals(titel)) {
                return i;
            }
        }
        throw new AssertionError("nicht gefunden: " + titel);
    }

    private void einsortieren(Path sammlung, String... pfadeUndInhalte) throws IOException {
        Path quelle = basis.resolve("quelle-" + sammlung.getFileName());
        for (int i = 0; i < pfadeUndInhalte.length; i += 2) {
            Path datei = quelle.resolve(pfadeUndInhalte[i]);
            Files.createDirectories(datei.getParent());
            Files.writeString(datei, pfadeUndInhalte[i + 1]);
        }
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, sammlung), sammlung, new PrintStream(OutputStream.nullOutputStream()));
    }
}
