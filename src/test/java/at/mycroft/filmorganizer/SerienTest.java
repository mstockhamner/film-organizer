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

/** Filme und Serienfolgen gemischt in einer Quelle. */
class SerienTest {

    @TempDir
    Path quelle;

    Path ziel;
    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void anlegen() throws IOException {
        ziel = quelle.resolve("Sammlung");
        datei("Das.Dschungelbuch.2016.German.DTSHD.1080p.BluRay.x264-FDHQ/jungle.mkv", "film");
        datei("Breaking.Bad.S01.German.DL.1080p.BluRay.x264-GRP/grp-bb-s01e01.mkv", "folge 1");
        datei("Breaking.Bad.S01.German.DL.1080p.BluRay.x264-GRP/grp-bb-s01e02.mkv", "folge 2");
        datei("Dark/Staffel 2/s02e05.mp4", "dark");
        datei("Dark.S02E06.German.720p.WEBRip-GRP/grp-dark-720p.mkv", "dark 6");
        datei("Dark.S02E06.German.720p.WEBRip-GRP/cover.jpg", "bild");
    }

    @Test
    void folgenKommenInDenSerienordner() throws IOException {
        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        assertEquals(5, plan.stream().filter(a -> a.art() == Art.EINSORTIEREN).count());
        assertEquals(5, Ausfuehrung.ausfuehren(plan, ziel, still));

        assertTrue(Files.exists(ziel.resolve("Das Dschungelbuch (2016)/Das Dschungelbuch (2016).mkv")));
        assertTrue(Files.exists(ziel.resolve("Serien/Breaking Bad/Staffel 01/Breaking Bad - S01E01.mkv")));
        assertTrue(Files.exists(ziel.resolve("Serien/Breaking Bad/Staffel 01/Breaking Bad - S01E02.mkv")));
        assertTrue(Files.exists(ziel.resolve("Serien/Dark/Staffel 02/Dark - S02E05.mp4")));
        assertTrue(Files.exists(ziel.resolve("Serien/Dark/Staffel 02/Dark - S02E06.mkv")));
        assertTrue(Files.exists(ziel.resolve("Serien/Dark/Staffel 02/Dark - S02E06.jpg")));
    }

    @Test
    void gleicheFolgeIstEineDoublette() throws IOException {
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);
        datei("Neu/Breaking.Bad.S01E02.720p.mkv", "andere fassung");
        datei("Neu/Breaking.Bad.S01E03.720p.mkv", "folge 3");

        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        assertEquals(Art.DOUBLETTE, art(plan, "Breaking.Bad.S01E02.720p.mkv"));
        assertEquals(Art.EINSORTIEREN, art(plan, "Breaking.Bad.S01E03.720p.mkv"));
    }

    @Test
    void vonHandAbgelegteFolgeZaehltAlsVorhanden() throws IOException {
        Path vorhanden = ziel.resolve("Serien/Dark/Staffel 02/Dark - S02E05.mkv");
        Files.createDirectories(vorhanden.getParent());
        Files.writeString(vorhanden, "alte, größere Datei"); // größer als die neue, also keine Ersetzung
        assertEquals(Art.DOUBLETTE, art(Main.planErstellen(quelle, ziel), "s02e05.mp4"));
    }

    @Test
    void rueckgaengigRaeumtSerienordnerAuf() throws IOException {
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);
        assertEquals(6, Ausfuehrung.rueckgaengig(ziel, still));
        assertFalse(Files.exists(ziel.resolve("Serien")));
        assertTrue(Files.exists(quelle.resolve("Dark/Staffel 2/s02e05.mp4")));
    }

    @Test
    void korrigierterSerientitelBehaeltStaffelUndFolge() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        Path video = quelle.resolve("Dark/Staffel 2/s02e05.mp4").toAbsolutePath().normalize();
        v.korrigieren(video, "Dark (Netflix)", 0);
        Aktion aktion = v.plan().stream().filter(a -> a.film().video().equals(video)).findFirst().orElseThrow();
        assertEquals(Art.EINSORTIEREN, aktion.art());
        assertEquals(ziel.resolve("Serien/Dark (Netflix)/Staffel 02/Dark (Netflix) - S02E05.mp4"),
                aktion.verschiebungen().get(0).nach());
    }

    private static Art art(List<Aktion> plan, String dateiname) {
        return plan.stream().filter(a -> a.film().video().getFileName().toString().equals(dateiname))
                .findFirst().orElseThrow().art();
    }

    private void datei(String pfad, String inhalt) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, inhalt);
    }
}
