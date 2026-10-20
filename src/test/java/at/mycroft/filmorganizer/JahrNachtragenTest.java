package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion.Art;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ein Film ohne Jahr in der Sammlung passt zum selben Film mit Jahr; das Jahr lässt sich ergänzen. */
class JahrNachtragenTest {

    @TempDir
    Path basis;

    Path quelle;
    Path ziel;
    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void filmOhneJahrInDerSammlung() throws IOException {
        quelle = basis.resolve("Quelle");
        ziel = basis.resolve("Sammlung");
        Path alt = datei("Alt/irgendwas.mkv", "der alte Pate, mittelgroß");
        datei("Alt/cover.jpg", "bild");
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        v.korrigieren(alt, "Der Pate", 0);
        Ausfuehrung.ausfuehren(v.plan(), quelle, ziel, still);
        assertTrue(Files.exists(ziel.resolve("Der Pate/Der Pate.mkv")));
        assertTrue(Files.exists(ziel.resolve("Der Pate/poster.jpg")));
    }

    @Test
    void schlechtereFassungMitJahrErgaenztDasJahr() throws IOException {
        Path neu = datei("Der.Pate.1972.German/pate.mkv", "klein");
        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        assertTrue(aktion(plan, neu).inQuarantaene(), "als derselbe Film erkannt, kleiner");
        Aktion jahr = plan.stream().filter(a -> a.art() == Art.JAHR_ERGAENZEN).findFirst().orElseThrow();
        assertEquals("Jahr ergänzen: Der Pate wird Der Pate (1972)", jahr.hinweis());

        Ausfuehrung.ausfuehren(plan, quelle, ziel, still);
        assertTrue(Files.exists(ziel.resolve("Der Pate (1972)/Der Pate (1972).mkv")));
        assertTrue(Files.exists(ziel.resolve("Der Pate (1972)/poster.jpg")));
        assertFalse(Files.exists(ziel.resolve("Der Pate")), "alter Ordner ist weg");
        Katalog.Eintrag eintrag = Katalog.laden(ziel).alle(new FilmInfo("Der Pate", 1972, 0).schluessel()).get(0);
        assertEquals("Der Pate (1972)/Der Pate (1972).mkv", eintrag.video().replace('\\', '/'));

        // Rückgängig: wieder ohne Jahr, auch im Katalog.
        Ausfuehrung.rueckgaengig(ziel, still);
        assertTrue(Files.exists(ziel.resolve("Der Pate/Der Pate.mkv")));
        assertFalse(Files.exists(ziel.resolve("Der Pate (1972)")));
        assertEquals(1, Katalog.laden(ziel).alle(new FilmInfo("Der Pate", 0, 0).schluessel()).size());
    }

    @Test
    void bessereFassungMitJahrErsetztUndBringtDasJahrMit() throws IOException {
        Path neu = datei("Der.Pate.1972.German.1080p/pate.mkv", "die neue, viel größere Fassung des Paten");
        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        Aktion ersetzen = aktion(plan, neu);
        assertEquals(Art.ERSETZEN, ersetzen.art());
        assertTrue(plan.stream().noneMatch(a -> a.art() == Art.JAHR_ERGAENZEN));

        Ausfuehrung.ausfuehren(plan, quelle, ziel, still);
        assertTrue(Files.exists(ziel.resolve("Der Pate (1972)/Der Pate (1972).mkv")));
        assertFalse(Files.exists(ziel.resolve("Der Pate")), "alte Fassung samt Cover in der Quarantäne");
        assertTrue(Files.exists(Planer.inQuarantaene(ziel, ziel.resolve("Der Pate/poster.jpg"))));
        assertEquals(1, Katalog.laden(ziel).eintraege().size(), "kein alter Eintrag ohne Jahr mehr");
    }

    @Test
    void neueFassungOhneJahrUebernimmtDasJahrDerSammlung() throws IOException {
        // Sammlung mit Jahr anlegen, dann eine Fassung ohne Jahr einlesen.
        Path mitJahr = basis.resolve("MitJahr");
        Path quelle2 = basis.resolve("Quelle2");
        Path a = quelle2.resolve("Alien.1979.German.1080p/alien.mkv");
        Files.createDirectories(a.getParent());
        Files.writeString(a, "alien gross");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle2, mitJahr), mitJahr, still);
        Path b = quelle2.resolve("Neu/x.mkv");
        Files.createDirectories(b.getParent());
        Files.writeString(b, "alien");
        Vorschlaege v = Vorschlaege.einlesen(quelle2, mitJahr);
        v.korrigieren(b.toAbsolutePath().normalize(), "Alien", 0);
        Aktion aktion = aktion(v.plan(), b.toAbsolutePath().normalize());
        assertEquals(Art.DOUBLETTE, aktion.art());
        assertEquals(1979, aktion.film().info().jahr());
    }

    private static Aktion aktion(List<Aktion> plan, Path video) {
        return plan.stream().filter(a -> a.film().video().equals(video)).findFirst().orElseThrow();
    }

    private Path datei(String pfad, String inhalt) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, inhalt);
        return datei.toAbsolutePath().normalize();
    }
}
