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
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scan, Plan, Ausführung und Rückgängig an einer nachgebauten Sammlung wie in F:\Daten-Filmorganizer. */
class AblaufTest {

    @TempDir
    Path quelle;

    Path ziel;
    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void sammlungAnlegen() throws IOException {
        ziel = quelle.resolve("Sammlung");
        datei("Das.Dschungelbuch.2016.German.DTSHD.1080p.BluRay.x264-FDHQ/WodkaE-The.Jungle.Book.1080p.mkv", "dschungel");
        datei("Das.Dschungelbuch.2016.German.DTSHD.1080p.BluRay.x264-FDHQ/cover.PNG", "bild");
        datei("Das.Dschungelbuch.2016.German.DTSHD.1080p.BluRay.x264-FDHQ/Thumbs.db", "x");
        datei("Der gestiefelte Kater (1080p)/(2011) Der gestiefelte Kater (1080p)/lhd-kater-1080p.mkv", "kater");
        datei("Der gestiefelte Kater (1080p)/(2011) Der gestiefelte Kater (1080p)/cover.jpg", "bild");
        datei("Unbekannt/irgendwas.mkv", "unbekannt");
    }

    @Test
    void ersterLaufSortiertEinUndLaesstResteLiegen() throws IOException {
        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        Map<Art, Long> arten = plan.stream().collect(Collectors.groupingBy(Aktion::art, Collectors.counting()));
        assertEquals(2L, arten.get(Art.EINSORTIEREN));
        assertEquals(1L, arten.get(Art.UNERKANNT));

        assertEquals(2, Ausfuehrung.ausfuehren(plan, ziel, still));

        assertTrue(Files.exists(ziel.resolve("Das Dschungelbuch (2016)/Das Dschungelbuch (2016).mkv")));
        assertTrue(Files.exists(ziel.resolve("Das Dschungelbuch (2016)/poster.png")));
        assertTrue(Files.exists(ziel.resolve("Der gestiefelte Kater (2011)/Der gestiefelte Kater (2011).mkv")));
        assertTrue(Files.exists(ziel.resolve("Der gestiefelte Kater (2011)/poster.jpg")));
        assertTrue(Files.exists(quelle.resolve("Das.Dschungelbuch.2016.German.DTSHD.1080p.BluRay.x264-FDHQ/Thumbs.db")));
        assertTrue(Files.exists(quelle.resolve("Unbekannt/irgendwas.mkv")));
    }

    @Test
    void zweiterLaufErkenntDoublettenGegenDieSammlung() throws IOException {
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);
        datei("Das Dschungelbuch (2016) 720p/dschungel.mkv", "andere fassung");
        datei("Kopie/Jungle.Book.Kopie.2015.mkv", "kater");

        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        List<Aktion> doubletten = plan.stream().filter(a -> a.art() == Art.DOUBLETTE).toList();
        assertEquals(2, doubletten.size());
        assertTrue(doubletten.get(0).hinweis().contains("vorhandene Fassung ist besser"));
        assertTrue(doubletten.get(1).hinweis().contains("identische Datei"));
        assertTrue(plan.stream().noneMatch(a -> a.art() == Art.EINSORTIEREN));
    }

    @Test
    void doublettenInnerhalbDesImportsBehaltenDieBessereFassung() throws IOException {
        datei("Das Dschungelbuch (2016) 720p/dschungel.mkv", "andere fassung");
        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        Aktion doublette = plan.stream().filter(a -> a.art() == Art.DOUBLETTE).findFirst().orElseThrow();
        assertTrue(doublette.film().video().toString().contains("720p"));
    }

    @Test
    void rueckgaengigStelltDenAltenZustandHer() throws IOException {
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);
        assertEquals(4, Ausfuehrung.rueckgaengig(ziel, still));

        assertTrue(Files.exists(quelle.resolve(
                "Der gestiefelte Kater (1080p)/(2011) Der gestiefelte Kater (1080p)/lhd-kater-1080p.mkv")));
        assertFalse(Files.exists(ziel.resolve("Der gestiefelte Kater (2011)")));
        assertEquals(-1, Ausfuehrung.rueckgaengig(ziel, still));
        assertEquals(2, Main.planErstellen(quelle, ziel).stream().filter(a -> a.art() == Art.EINSORTIEREN).count());
    }

    @Test
    void vonHandAngelegterZielordnerZaehltAlsVorhanden() throws IOException {
        Files.createDirectories(ziel.resolve("Das Dschungelbuch (2016)"));
        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        assertEquals(1, plan.stream().filter(a -> a.art() == Art.DOUBLETTE).count());
    }

    @Test
    void windowsVerboteneZeichenWerdenErsetzt() {
        assertEquals("Mission - Impossible", Dateinamen.bereinigen("Mission: Impossible"));
        assertEquals("Was ist das", Dateinamen.bereinigen("Was ist das?"));
    }

    private void datei(String pfad, String inhalt) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, inhalt);
    }
}
