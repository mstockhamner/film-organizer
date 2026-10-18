package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import at.mycroft.filmorganizer.Aktion.Art;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VorschlaegeTest {

    @TempDir
    Path quelle;

    Path ziel;
    Path dschungel;
    Path unbekannt;

    @BeforeEach
    void anlegen() throws IOException {
        ziel = quelle.resolve("Sammlung");
        dschungel = datei("Das.Dschungelbuch.2016.German.1080p.BluRay.x264-FDHQ/jungle.mkv", "a");
        unbekannt = datei("Unbekannt/irgendwas.720p.mkv", "bb");
    }

    @Test
    void schlaegtErkannteTitelVorUndLaesstUnbekannteOffen() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        List<Aktion> plan = v.plan();
        assertEquals(Art.EINSORTIEREN, art(plan, dschungel));
        assertEquals(Art.UNERKANNT, art(plan, unbekannt));
        assertEquals("Das Dschungelbuch", v.titel(film(plan, dschungel)));
        assertEquals(0, v.jahr(film(plan, unbekannt)));
    }

    @Test
    void korrekturAendertZielordner() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        v.korrigieren(dschungel, "The Jungle Book", 2016);
        Aktion aktion = aktion(v.plan(), dschungel);
        assertEquals(ziel.resolve("The Jungle Book (2016)/The Jungle Book (2016).mkv"),
                aktion.verschiebungen().get(0).nach());
    }

    @Test
    void unbekannterFilmWirdMitTitelUndJahrEinsortiert() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        v.korrigieren(unbekannt, "Der Pate", 0);
        assertEquals(Art.UNERKANNT, art(v.plan(), unbekannt));
        v.korrigieren(unbekannt, "Der Pate", 1972);
        Aktion aktion = aktion(v.plan(), unbekannt);
        assertEquals(Art.EINSORTIEREN, aktion.art());
        assertEquals(720, aktion.film().info().aufloesung());
    }

    @Test
    void korrekturKannEineDoubletteErzeugen() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        v.korrigieren(unbekannt, "Das Dschungelbuch", 2016);
        List<Aktion> plan = v.plan();
        assertEquals(Art.EINSORTIEREN, art(plan, dschungel));
        assertEquals(Art.DOUBLETTE, art(plan, unbekannt));
    }

    @Test
    void abgewaehlterFilmBleibtLiegen() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        v.auslassen(dschungel, true);
        List<Aktion> plan = v.plan();
        assertEquals(Art.AUSGELASSEN, art(plan, dschungel));
        assertEquals(0, Ausfuehrung.ausfuehren(plan, ziel, new java.io.PrintStream(java.io.OutputStream.nullOutputStream())));
        assertEquals(true, Files.exists(dschungel));
        v.auslassen(dschungel, false);
        assertEquals(Art.EINSORTIEREN, art(v.plan(), dschungel));
    }

    private static Aktion aktion(List<Aktion> plan, Path video) {
        return plan.stream().filter(a -> a.film().video().equals(video)).findFirst().orElseThrow();
    }

    private static Art art(List<Aktion> plan, Path video) {
        return aktion(plan, video).art();
    }

    private static Film film(List<Aktion> plan, Path video) {
        return aktion(plan, video).film();
    }

    private Path datei(String pfad, String inhalt) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, inhalt);
        return datei.toAbsolutePath().normalize();
    }
}
