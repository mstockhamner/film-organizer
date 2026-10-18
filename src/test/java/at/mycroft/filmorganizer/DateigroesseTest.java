package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bei Doubletten mit gleicher oder unbekannter Auflösung wird die größere Datei behalten. */
class DateigroesseTest {

    @TempDir
    Path quelle;

    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @Test
    void vergleichZuerstNachAufloesungDannNachGroesse() {
        assertTrue(Planer.vergleichen(1080, 5, 720, 9) > 0, "höhere Auflösung schlägt größere Datei");
        assertTrue(Planer.vergleichen(1080, 5, 1080, 9) < 0, "gleiche Auflösung: kleinere Datei ist schlechter");
        assertTrue(Planer.vergleichen(0, 9, 1080, 5) > 0, "Auflösung unbekannt: größere Datei gewinnt");
        assertEquals(0, Planer.vergleichen(1080, 5, 1080, 5));
        assertEquals(0, Planer.vergleichen(1080, 5, 1080, 0), "Größe unbekannt: nicht eindeutig");
    }

    @Test
    void imImportWirdDieGroessereDateiEinsortiertUndDieKleinereKommtInDieQuarantaene() throws IOException {
        Path gross = datei("Der.Pate.1972.German.1080p.BluRay/pate.mkv", "große datei, 1080p");
        Path klein = datei("Der.Pate.1972.German.1080p.WEB/pate.mkv", "klein, 1080p");
        List<Aktion> plan = Main.planErstellen(quelle, ziel());
        assertEquals(Art.EINSORTIEREN, aktion(plan, gross).art());
        assertEquals(Entscheidung.QUARANTAENE, aktion(plan, klein).vorschlag());
        assertTrue(aktion(plan, klein).inQuarantaene());
    }

    @Test
    void gegenDieSammlungErsetztDieGroessereDatei() throws IOException {
        datei("Der.Pate.1972.German.1080p.WEB/pate.mkv", "klein, 1080p");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel()), ziel(), still);
        Path gross = datei("Der.Pate.1972.German.1080p.BluRay/pate.mkv", "große datei, 1080p");
        Path kleiner = datei("Der.Pate.1972.German.1080p.TV/pate.mkv", "1080p");

        List<Aktion> plan = Main.planErstellen(quelle, ziel());
        Aktion ersetzen = aktion(plan, gross);
        assertEquals(Art.ERSETZEN, ersetzen.art());
        assertTrue(ersetzen.hinweis().contains("neue Datei ist größer"));
        assertTrue(aktion(plan, kleiner).inQuarantaene());
    }

    @Test
    void vonHandAbgelegterFilmWirdUeberDieDateigroesseVerglichen() throws IOException {
        Path hand = ziel().resolve("Der Pate (1972)/pate.avi");
        Files.createDirectories(hand.getParent());
        Files.writeString(hand, "klein");
        Path gross = datei("Der.Pate.1972.German/pate.mkv", "eine deutlich größere datei");
        assertEquals(Art.ERSETZEN, aktion(Main.planErstellen(quelle, ziel()), gross).art());
    }

    @Test
    void hoehereAufloesungGehtVorGroesse() throws IOException {
        datei("Der.Pate.1972.German.1080p.BluRay/pate.mkv", "1080p");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel()), ziel(), still);
        Path gross720 = datei("Der.Pate.1972.German.720p/pate.mkv", "720p, aber viel größer als die vorhandene");
        assertTrue(aktion(Main.planErstellen(quelle, ziel()), gross720).inQuarantaene());
    }

    private Path ziel() {
        return quelle.resolve("Sammlung");
    }

    private static Aktion aktion(List<Aktion> plan, Path video) {
        return plan.stream().filter(a -> a.film().video().toAbsolutePath().normalize().equals(video))
                .findFirst().orElseThrow();
    }

    private Path datei(String pfad, String inhalt) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, inhalt);
        return datei.toAbsolutePath().normalize();
    }
}
