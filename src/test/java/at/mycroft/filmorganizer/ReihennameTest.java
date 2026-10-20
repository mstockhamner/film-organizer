package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion.Art;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bei Reihen mit mehr als drei Filmen behalten nummerierte Filme den Reihennamen im Titel. */
class ReihennameTest {

    @TempDir
    Path basis;

    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @Test
    void abVierFilmenMitReihenname() throws IOException {
        Path quelle = basis.resolve("Quelle");
        Path ziel = basis.resolve("Sammlung");
        Path eins = datei(quelle, "Star Trek 01 - Der Film (1979).mkv", "1");
        datei(quelle, "Star Trek 02 - Der Zorn des Khan (1982).mkv", "2");
        datei(quelle, "Star Trek 03 - Auf der Suche nach Mr Spock (1984).mkv", "3");
        datei(quelle, "Star Trek 04 - Zurück in die Gegenwart (1986).mkv", "4");

        Aktion aktion = aktion(Main.planErstellen(quelle, ziel), eins);
        assertEquals(ziel.resolve("Star Trek/01 - Star Trek - Der Film (1979)/Star Trek - Der Film (1979).mkv"),
                aktion.verschiebungen().get(0).nach());
        assertEquals("Star Trek - Der Film", aktion.film().info().titel());
    }

    @Test
    void biszuDreiFilmenOhneReihenname() throws IOException {
        Path quelle = basis.resolve("Quelle");
        Path ziel = basis.resolve("Sammlung");
        Path eins = datei(quelle, "Star Trek 01 - Der Film (1979).mkv", "1");
        datei(quelle, "Star Trek 02 - Der Zorn des Khan (1982).mkv", "2");
        datei(quelle, "Star Trek 03 - Auf der Suche nach Mr Spock (1984).mkv", "3");
        assertEquals(ziel.resolve("Star Trek/01 - Der Film (1979)/Der Film (1979).mkv"),
                aktion(Main.planErstellen(quelle, ziel), eins).verschiebungen().get(0).nach());
    }

    @Test
    void waechstDieReiheUeberDreiWerdenVorhandeneUmbenannt() throws IOException {
        Path quelle = basis.resolve("Quelle");
        Path ziel = basis.resolve("Sammlung");
        datei(quelle, "Star Trek 01 - Der Film (1979).mkv", "1");
        datei(quelle, "Star Trek 02 - Der Zorn des Khan (1982).mkv", "2");
        datei(quelle, "Star Trek 03 - Auf der Suche nach Mr Spock (1984).mkv", "3");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), quelle, ziel, still);
        assertTrue(Files.exists(ziel.resolve("Star Trek/01 - Der Film (1979)/Der Film (1979).mkv")));
        Files.writeString(ziel.resolve("Star Trek/01 - Der Film (1979)/Der Film (1979).de.srt"), "untertitel");

        Path vier = datei(quelle, "Star Trek 04 - Zurück in die Gegenwart (1986).mkv", "4");
        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        assertEquals(ziel.resolve("Star Trek/04 - Star Trek - Zurück in die Gegenwart (1986)/"
                + "Star Trek - Zurück in die Gegenwart (1986).mkv"), aktion(plan, vier).verschiebungen().get(0).nach());
        List<Aktion> umbenennen = plan.stream().filter(a -> a.art() == Art.UMBENENNEN).toList();
        assertEquals(3, umbenennen.size());
        assertTrue(umbenennen.stream().anyMatch(a -> a.hinweis().equals(
                "Reihenname in den Titel: Der Film (1979) wird Star Trek - Der Film (1979)")));

        Ausfuehrung.ausfuehren(plan, quelle, ziel, still);
        Path ordner = ziel.resolve("Star Trek/01 - Star Trek - Der Film (1979)");
        assertTrue(Files.exists(ordner.resolve("Star Trek - Der Film (1979).mkv")));
        assertTrue(Files.exists(ordner.resolve("Star Trek - Der Film (1979).de.srt")), "Begleitdatei mit umbenannt");
        assertTrue(Files.notExists(ziel.resolve("Star Trek/01 - Der Film (1979)")));
        assertTrue(Main.planErstellen(quelle, ziel).isEmpty(), "danach ist alles stimmig");

        // Derselbe Film noch einmal, mit kurzem Titel: trotzdem eine Doublette.
        Path nochmal = datei(quelle, "Neu/Star Trek 02 - Der Zorn des Khan (1982).mkv", "2");
        assertEquals(Art.DOUBLETTE, aktion(Main.planErstellen(quelle, ziel), nochmal).art());
    }

    @Test
    void basisTitelOhneReihenname() {
        FilmInfo info = new FilmInfo("Star Trek - Der Film", 1979, 0, 0, 0, "", "Star Trek");
        assertEquals("Der Film", info.basisTitel());
        assertEquals(new FilmInfo("Der Film", 1979, 0, 0, 0, "", "Star Trek").schluessel(), info.schluessel());
        assertEquals("Harry Potter und der Stein der Weisen",
                new FilmInfo("Harry Potter und der Stein der Weisen", 2001, 0, 0, 0, "", "Harry Potter").basisTitel());
    }

    private static Aktion aktion(List<Aktion> plan, Path video) {
        return plan.stream().filter(a -> a.film().video().equals(video)).findFirst().orElseThrow();
    }

    private static Path datei(Path quelle, String pfad, String inhalt) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, inhalt);
        return datei.toAbsolutePath().normalize();
    }
}
