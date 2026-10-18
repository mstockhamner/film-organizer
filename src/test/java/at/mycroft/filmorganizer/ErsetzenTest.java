package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Eine bessere Fassung ersetzt die vorhandene, die alte kommt in die Quarantäne. */
class ErsetzenTest {

    @TempDir
    Path quelle;

    Path ziel;
    Path ordner;
    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void sammlungEinsortieren() throws IOException {
        ziel = quelle.resolve("Sammlung").toAbsolutePath().normalize();
        ordner = ziel.resolve("Das Dschungelbuch (2016)");
        datei("Das.Dschungelbuch.2016.German.1080p.BluRay.x264-FDHQ/jungle.mkv", "alt 1080p");
        datei("Das.Dschungelbuch.2016.German.1080p.BluRay.x264-FDHQ/cover.jpg", "altes bild");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);
    }

    @Test
    void bessereFassungErsetztDieVorhandene() throws IOException {
        Path neu = datei("Das.Dschungelbuch.2016.German.2160p.UHD.BluRay.x265-GRP/jungle-uhd.mkv", "neu 2160p");
        datei("Das.Dschungelbuch.2016.German.2160p.UHD.BluRay.x265-GRP/cover.jpg", "neues bild");

        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        Aktion aktion = aktion(plan, neu);
        assertEquals(Art.ERSETZEN, aktion.art());
        assertEquals(Entscheidung.ERSETZEN, aktion.vorschlag());
        assertTrue(aktion.hinweis().contains("neue Fassung ist besser"));
        assertEquals(1, Ausfuehrung.ausfuehren(plan, ziel, still));

        assertEquals("neu 2160p", Files.readString(ordner.resolve("Das Dschungelbuch (2016).mkv")));
        assertEquals("neues bild", Files.readString(ordner.resolve("poster.jpg")));
        assertEquals("alt 1080p", Files.readString(inQuarantaene(ordner.resolve("Das Dschungelbuch (2016).mkv"))));
        assertEquals("altes bild", Files.readString(inQuarantaene(ordner.resolve("poster.jpg"))));

        // Der Katalog kennt jetzt die 2160p-Fassung: eine weitere 1080p ist schlechter.
        Path nochmal = datei("Das Dschungelbuch (2016) 1080p/dschungel.mkv", "noch eine 1080p");
        assertTrue(aktion(Main.planErstellen(quelle, ziel), nochmal).inQuarantaene());
    }

    @Test
    void rueckgaengigStelltDieAlteFassungWiederHer() throws IOException {
        Path neu = datei("Das.Dschungelbuch.2016.German.2160p.UHD.BluRay.x265-GRP/jungle-uhd.mkv", "neu 2160p");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);

        assertEquals(2, Ausfuehrung.rueckgaengig(ziel, still));
        assertEquals("alt 1080p", Files.readString(ordner.resolve("Das Dschungelbuch (2016).mkv")));
        assertEquals("neu 2160p", Files.readString(neu));
        assertFalse(Files.exists(ziel.resolve(Planer.QUARANTAENE)));
    }

    @Test
    void vonHandAngelegterFilmKannAufWunschErsetztWerden() throws IOException {
        Path hand = ziel.resolve("Der Pate (1972)/pate.avi");
        Files.createDirectories(hand.getParent());
        Files.writeString(hand, "alt");
        // Auflösung der alten Datei unbekannt, beide gleich groß: nicht eindeutig, also nur auf Wunsch ersetzen.
        Path neu = datei("Der.Pate.1972.German.1080p.BluRay/pate.mkv", "neu");

        Aktion vorschlag = aktion(Main.planErstellen(quelle, ziel), neu);
        assertEquals(Art.DOUBLETTE, vorschlag.art());
        assertEquals(Entscheidung.LIEGEN_LASSEN, vorschlag.vorschlag());
        assertTrue(vorschlag.auswahl().contains(Entscheidung.ERSETZEN));

        List<Aktion> plan = Main.planErstellen(quelle, ziel, Map.of(neu, Entscheidung.ERSETZEN));
        assertEquals(Art.ERSETZEN, aktion(plan, neu).art());
        Ausfuehrung.ausfuehren(plan, ziel, still);
        assertTrue(Files.exists(ziel.resolve("Der Pate (1972)/Der Pate (1972).mkv")));
        assertFalse(Files.exists(hand));
        assertTrue(Files.exists(inQuarantaene(hand)));
    }

    @Test
    void schalterLiegenLaesstAlleDoublettenLiegen() throws IOException {
        Path neu = datei("Das.Dschungelbuch.2016.German.2160p.UHD.BluRay.x265-GRP/jungle-uhd.mkv", "neu 2160p");
        Path kopie = datei("Kopie/Irgendwas.2001.mkv", "alt 1080p");
        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        plan = Main.planErstellen(quelle, ziel, Main.allesLiegenLassen(plan, Map.of()));
        assertEquals(Art.DOUBLETTE, aktion(plan, neu).art());
        assertTrue(aktion(plan, neu).verschiebungen().isEmpty());
        assertFalse(aktion(plan, kopie).inQuarantaene());
    }

    @Test
    void berichtListetDoublettenMitEntscheidung() throws IOException {
        datei("Das.Dschungelbuch.2016.German.2160p.UHD.BluRay.x265-GRP/jungle-uhd.mkv", "neu 2160p");
        datei("Kopie/Irgendwas.2001.mkv", "alt 1080p");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);

        Path berichte = ziel.resolve(Katalog.VERWALTUNG).resolve(Ausfuehrung.BERICHTE);
        List<Path> dateien;
        try (Stream<Path> s = Files.list(berichte)) {
            dateien = s.toList();
        }
        assertEquals(1, dateien.size());
        String bericht = Files.readString(dateien.get(0));
        assertTrue(bericht.contains("Ersetzen (Vorschlag)") || bericht.contains("Ersetzen: "));
        assertTrue(bericht.contains("In Quarantäne: "));
        assertTrue(bericht.contains("identische Datei"));
    }

    private Path inQuarantaene(Path datei) {
        Path absolut = datei.toAbsolutePath().normalize();
        return ziel.resolve(Planer.QUARANTAENE).resolve(LocalDate.now().toString())
                .resolve(absolut.getRoot().relativize(absolut));
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
