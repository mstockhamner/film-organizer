package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Extended, Director's Cut und Kinofassung desselben Films: nachfragen statt als Doublette behandeln. */
class FassungenTest {

    @TempDir
    Path quelle;

    Path ziel;
    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void kinofassungEinsortieren() throws IOException {
        ziel = quelle.resolve("Sammlung");
        datei("Der.Herr.der.Ringe.2001.German.1080p.BluRay.x264-GRP/hdr.mkv", "kino 1080p");
        datei("Der.Herr.der.Ringe.2001.German.1080p.BluRay.x264-GRP/cover.jpg", "bild");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Der.Herr.der.Ringe.2001.EXTENDED.German.720p.BluRay | Extended
            Blade.Runner.1982.Directors.Cut.German.1080p        | Director's Cut
            Blade Runner (1982) Director's Cut                  | Director's Cut
            Blade.Runner.1982.Final.Cut.2160p                   | Final Cut
            Alien.1979.Kinofassung.German.1080p                 | ''
            Das.Dschungelbuch.2016.German.1080p                 | ''
            """)
    void erkenntFassungen(String name, String fassung) {
        assertEquals(fassung, NamensErkennung.erkennen(name).orElseThrow().fassung());
    }

    @Test
    void andereFassungWirdNachgefragtAuchWennSieSchlechterIst() throws IOException {
        Path extended = datei("Der.Herr.der.Ringe.2001.EXTENDED.German.720p.BluRay/hdr-ext.mkv", "extended 720p");
        Aktion aktion = aktion(Main.planErstellen(quelle, ziel), extended);
        assertEquals(Art.FASSUNG, aktion.art());
        assertTrue(aktion.hinweis().contains("Extended"));
        assertTrue(aktion.hinweis().contains("Kinofassung"));

        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);
        assertTrue(Files.exists(extended));
    }

    @Test
    void beideBehaltenSortiertInDenselbenOrdnerEin() throws IOException {
        Path extended = datei("Der.Herr.der.Ringe.2001.EXTENDED.German.720p.BluRay/hdr-ext.mkv", "extended 720p");
        datei("Der.Herr.der.Ringe.2001.EXTENDED.German.720p.BluRay/cover.jpg", "bild ext");
        List<Aktion> plan = Main.planErstellen(quelle, ziel, Map.of(extended, Entscheidung.BEIDE_BEHALTEN));
        assertEquals(Art.EINSORTIEREN, aktion(plan, extended).art());
        Ausfuehrung.ausfuehren(plan, ziel, still);

        Path ordner = ziel.resolve("Der Herr der Ringe (2001)");
        assertTrue(Files.exists(ordner.resolve("Der Herr der Ringe (2001).mkv")));
        assertTrue(Files.exists(ordner.resolve("poster.jpg")));
        assertTrue(Files.exists(ordner.resolve("Der Herr der Ringe (2001) - Extended.mkv")));
        assertTrue(Files.exists(ordner.resolve("Der Herr der Ringe (2001) - Extended.jpg")));

        // Beim nächsten Lauf sind beide Fassungen bekannt: eine weitere Extended ist eine Doublette.
        datei("Noch.einmal/Der.Herr.der.Ringe.2001.Extended.480p.mkv", "extended 480p");
        Aktion nochmal = Main.planErstellen(quelle, ziel).stream()
                .filter(a -> a.film().video().getFileName().toString().contains("480p")).findFirst().orElseThrow();
        assertTrue(nochmal.inQuarantaene());
    }

    @Test
    void quarantaeneAufWunsch() throws IOException {
        Path extended = datei("Der.Herr.der.Ringe.2001.EXTENDED.German.720p.BluRay/hdr-ext.mkv", "extended 720p");
        List<Aktion> plan = Main.planErstellen(quelle, ziel, Map.of(extended, Entscheidung.QUARANTAENE));
        assertTrue(aktion(plan, extended).inQuarantaene());
    }

    @Test
    void zweiFassungenImSelbenImportWerdenNachgefragt() throws IOException {
        Path kino = datei("Blade.Runner.1982.German.1080p/br.mkv", "kino");
        Path dc = datei("Blade.Runner.1982.Directors.Cut.German.2160p/br-dc.mkv", "dc");
        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        // Die bessere Fassung wird einsortiert, nach der anderen wird gefragt.
        assertEquals(Art.EINSORTIEREN, aktion(plan, dc).art());
        assertEquals(Art.FASSUNG, aktion(plan, kino).art());
    }

    @Test
    void kommandozeileFragtNach() throws IOException {
        Path extended = datei("Der.Herr.der.Ringe.2001.EXTENDED.German.720p.BluRay/hdr-ext.mkv", "extended 720p");
        Map<Path, Entscheidung> antwort = Main.fassungenNachfragen(Main.planErstellen(quelle, ziel),
                new BufferedReader(new StringReader("b\n")), still);
        assertEquals(Map.of(extended, Entscheidung.BEIDE_BEHALTEN), antwort);
        assertEquals(Map.of(extended, Entscheidung.LIEGEN_LASSEN), Main.fassungenNachfragen(
                Main.planErstellen(quelle, ziel), new BufferedReader(new StringReader("l\n")), still));
        Aktion liegen = aktion(Main.planErstellen(quelle, ziel, Map.of(extended, Entscheidung.LIEGEN_LASSEN)), extended);
        assertEquals(Art.FASSUNG, liegen.art());
        assertFalse(liegen.offen());
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
