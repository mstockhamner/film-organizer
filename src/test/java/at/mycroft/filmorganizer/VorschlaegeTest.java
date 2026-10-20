package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
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
        assertEquals(Art.UNERKANNT, art(v.plan(), unbekannt));
        // Titel genügt, das Jahr ist keine Pflicht.
        v.korrigieren(unbekannt, "Der Pate", 0);
        assertEquals(ziel.resolve("Der Pate/Der Pate.mkv"), aktion(v.plan(), unbekannt).verschiebungen().get(0).nach());
        v.korrigieren(unbekannt, "Der Pate", 1972);
        Aktion aktion = aktion(v.plan(), unbekannt);
        assertEquals(Art.EINSORTIEREN, aktion.art());
        assertEquals(720, aktion.film().info().aufloesung());
    }

    @Test
    void dateinameAlsVorlaeufigerTitel() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        Aktion aktion = aktion(v.plan(), unbekannt);
        assertEquals(Art.UNERKANNT, aktion.art(), "bleibt liegen, bis korrekt benannt");
        assertEquals("irgendwas", v.titel(aktion.film()));
        assertEquals(Entscheidung.BENENNEN, aktion.entscheidung());
        assertEquals(List.of(Entscheidung.BENENNEN, Entscheidung.TITEL_UEBERNEHMEN), aktion.auswahl());

        // Nur ein Jahr eintragen reicht nicht: der Titel ist weiter der vorläufige.
        v.korrigieren(unbekannt, "irgendwas", 2001);
        assertEquals(Art.UNERKANNT, art(v.plan(), unbekannt));

        // Bewusst so übernehmen …
        v.entscheiden(unbekannt, Entscheidung.TITEL_UEBERNEHMEN);
        Aktion uebernommen = aktion(v.plan(), unbekannt);
        assertEquals(Art.EINSORTIEREN, uebernommen.art());
        assertEquals(ziel.resolve("irgendwas (2001)/irgendwas (2001).mkv"), uebernommen.verschiebungen().get(0).nach());
        assertEquals(Entscheidung.TITEL_UEBERNEHMEN, uebernommen.entscheidung());

        // … oder korrekt benennen.
        v.entscheiden(unbekannt, Entscheidung.BENENNEN);
        v.korrigieren(unbekannt, "Der Pate", 1972);
        assertEquals(ziel.resolve("Der Pate (1972)/Der Pate (1972).mkv"),
                aktion(v.plan(), unbekannt).verschiebungen().get(0).nach());
    }

    @Test
    void filmOhneJahrWirdBeimNaechstenLaufWiedererkannt() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        v.korrigieren(unbekannt, "Der Pate", 0);
        Ausfuehrung.ausfuehren(v.plan(), ziel, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        assertEquals(true, Files.exists(ziel.resolve("Der Pate/Der Pate.mkv")));

        Katalog.Eintrag eintrag = Katalog.laden(ziel).alle(new FilmInfo("Der Pate", 0, 0).schluessel()).get(0);
        assertEquals("", eintrag.info().reihe(), "Titel/Datei ohne Jahr ist kein Reihen-Ordner");
        Path nochmal = datei("Neu/nochmal.mkv", "andere");
        Vorschlaege zweiter = Vorschlaege.einlesen(quelle, ziel);
        zweiter.korrigieren(nochmal, "Der Pate", 0);
        Aktion aktion = aktion(zweiter.plan(), nochmal);
        assertEquals(Art.ERSETZEN, aktion.art(), "als vorhanden erkannt; die neue Datei ist größer");
        assertEquals(true, aktion.hinweis().startsWith("schon in der Sammlung: Der Pate"));
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
