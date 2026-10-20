package at.mycroft.filmorganizer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion;
import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Film;
import at.mycroft.filmorganizer.Vorschlaege;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanTabelleTest {

    @TempDir
    Path quelle;

    @Test
    void zeigtVorschlaegeUndUebernimmtAenderungen() throws IOException {
        Path ziel = quelle.resolve("Sammlung");
        datei("(2011) Der gestiefelte Kater (1080p)/lhd-kater-1080p.mkv");
        datei("Unbekannt/irgendwas.mkv");
        PlanTabelle tabelle = new PlanTabelle();
        int[] aenderungen = {0};
        tabelle.beiAenderung(() -> aenderungen[0]++);
        tabelle.anzeigen(Vorschlaege.einlesen(quelle, ziel), quelle);

        assertEquals(2, tabelle.getRowCount());
        assertEquals(Boolean.TRUE, tabelle.getValueAt(0, PlanTabelle.UEBERNEHMEN));
        assertEquals("Wird einsortiert", tabelle.getValueAt(0, PlanTabelle.STATUS));
        assertEquals("Der gestiefelte Kater", tabelle.getValueAt(0, PlanTabelle.TITEL));
        assertEquals(2011, tabelle.getValueAt(0, PlanTabelle.JAHR));
        assertEquals(Path.of("(2011) Der gestiefelte Kater (1080p)", "lhd-kater-1080p.mkv").toString(),
                tabelle.getValueAt(0, PlanTabelle.GEFUNDEN));
        assertEquals(Path.of("Der gestiefelte Kater (2011)", "Der gestiefelte Kater (2011).mkv").toString(),
                tabelle.getValueAt(0, PlanTabelle.NEUER_ORT));
        // "irgendwas.mkv": Dateiname als vorläufiger Titel, muss noch benannt werden.
        assertEquals(PlanTabelle.TITEL_PRUEFEN, tabelle.getValueAt(1, PlanTabelle.STATUS));
        assertEquals("irgendwas", tabelle.getValueAt(1, PlanTabelle.TITEL));
        assertEquals("vorläufiger Titel aus dem Dateinamen, bitte korrekt benennen",
                tabelle.getValueAt(1, PlanTabelle.NEUER_ORT));
        assertEquals("noch korrekt benennen (Vorschlag)", tabelle.getValueAt(1, PlanTabelle.ENTSCHEIDUNG));
        assertTrue(tabelle.isCellEditable(1, PlanTabelle.TITEL));

        tabelle.setValueAt("Der Pate", 1, PlanTabelle.TITEL);
        tabelle.setValueAt(1972, 1, PlanTabelle.JAHR);
        assertEquals("Wird einsortiert", tabelle.getValueAt(1, PlanTabelle.STATUS));
        assertEquals(Path.of("Der Pate (1972)", "Der Pate (1972).mkv").toString(),
                tabelle.getValueAt(1, PlanTabelle.NEUER_ORT));

        tabelle.setValueAt(Boolean.FALSE, 0, PlanTabelle.UEBERNEHMEN);
        assertEquals("Ausgelassen", tabelle.getValueAt(0, PlanTabelle.STATUS));
        assertEquals(3, aenderungen[0]);
    }

    @Test
    void entscheidungFuerAndereFassung() throws IOException {
        Path ziel = quelle.resolve("Sammlung");
        datei("Blade.Runner.1982.German.2160p/br.mkv");
        datei("Blade.Runner.1982.Directors.Cut.German.1080p/br-dc.mkv");
        PlanTabelle tabelle = new PlanTabelle();
        tabelle.anzeigen(Vorschlaege.einlesen(quelle, ziel), quelle);
        int dc = 0; // nach Fundort sortiert: "Directors" vor "German"
        assertEquals("Andere Fassung", tabelle.getValueAt(dc, PlanTabelle.STATUS));
        assertEquals("bitte wählen", tabelle.getValueAt(dc, PlanTabelle.ENTSCHEIDUNG));
        assertTrue(tabelle.isCellEditable(dc, PlanTabelle.ENTSCHEIDUNG));
        assertEquals(false, tabelle.isCellEditable(1, PlanTabelle.ENTSCHEIDUNG));

        tabelle.setValueAt("Beide behalten", dc, PlanTabelle.ENTSCHEIDUNG);
        assertEquals("Wird einsortiert", tabelle.getValueAt(dc, PlanTabelle.STATUS));
        assertEquals(Path.of("Blade Runner (1982)", "Blade Runner (1982) - Director's Cut.mkv").toString(),
                tabelle.getValueAt(dc, PlanTabelle.NEUER_ORT));
        assertTrue(tabelle.isCellEditable(dc, PlanTabelle.ENTSCHEIDUNG));
    }

    @Test
    void entscheidungFuerDoublette() throws IOException {
        Path ziel = quelle.resolve("Sammlung");
        datei("Der.Pate.1972.German.1080p/pate.mkv");
        datei("Der.Pate.1972.German.720p/pate.mkv");
        PlanTabelle tabelle = new PlanTabelle();
        tabelle.anzeigen(Vorschlaege.einlesen(quelle, ziel), quelle);
        int schlechter = 1;
        assertEquals(PlanTabelle.IN_QUARANTAENE, tabelle.getValueAt(schlechter, PlanTabelle.STATUS));
        assertEquals("In Quarantäne (Vorschlag)", tabelle.getValueAt(schlechter, PlanTabelle.ENTSCHEIDUNG));
        assertEquals(List.of("In Quarantäne (Vorschlag)", "Liegen lassen"), List.of(tabelle.auswahl(schlechter)));

        tabelle.setValueAt("Liegen lassen", schlechter, PlanTabelle.ENTSCHEIDUNG);
        assertEquals("Doublette", tabelle.getValueAt(schlechter, PlanTabelle.STATUS));
        assertEquals("Liegen lassen", tabelle.getValueAt(schlechter, PlanTabelle.ENTSCHEIDUNG));
    }

    @Test
    void filmreiheLaesstSichAendern() throws IOException {
        Path ziel = quelle.resolve("Sammlung");
        datei("Shrek.2001.German.1080p/shrek.mkv");
        datei("Shrek.2.2004.German.1080p/shrek2.mkv");
        PlanTabelle tabelle = new PlanTabelle();
        tabelle.anzeigen(Vorschlaege.einlesen(quelle, ziel), quelle);
        int shrek = 1; // nach Fundort sortiert: "Shrek.2.2004" vor "Shrek.2001"
        assertEquals("Shrek", tabelle.getValueAt(shrek, PlanTabelle.REIHE));
        assertEquals(Path.of("Shrek", "Shrek (2001)", "Shrek (2001).mkv").toString(),
                tabelle.getValueAt(shrek, PlanTabelle.NEUER_ORT));
        assertTrue(tabelle.isCellEditable(shrek, PlanTabelle.REIHE));

        tabelle.setValueAt("", shrek, PlanTabelle.REIHE);
        assertEquals("", tabelle.getValueAt(shrek, PlanTabelle.REIHE));
        assertEquals(Path.of("Shrek (2001)", "Shrek (2001).mkv").toString(), tabelle.getValueAt(shrek, PlanTabelle.NEUER_ORT));
    }

    @Test
    void mehrerenFilmenEineReiheZuweisen() throws IOException {
        Path ziel = quelle.resolve("Sammlung");
        datei("Batman.Begins.2005.German.1080p/bb.mkv");
        datei("The.Dark.Knight.2008.German.1080p/tdk.mkv");
        datei("Dark.S01E01.German.720p/dark.mkv");
        PlanTabelle tabelle = new PlanTabelle();
        int[] aenderungen = {0};
        tabelle.beiAenderung(() -> aenderungen[0]++);
        tabelle.anzeigen(Vorschlaege.einlesen(quelle, ziel), quelle);
        assertEquals(List.of(), tabelle.bekannteReihen());

        // Zeilen nach Fundort: Batman, Dark (Serienfolge), The Dark Knight.
        assertEquals(2, tabelle.reiheSetzen(new int[] {0, 1, 2}, "Batman"));
        assertEquals("Batman", tabelle.getValueAt(0, PlanTabelle.REIHE));
        assertEquals("", tabelle.getValueAt(1, PlanTabelle.REIHE));
        assertEquals(Path.of("Batman", "The Dark Knight (2008)", "The Dark Knight (2008).mkv").toString(),
                tabelle.getValueAt(2, PlanTabelle.NEUER_ORT));
        assertEquals(List.of("Batman"), tabelle.bekannteReihen());
        assertEquals(1, aenderungen[0]);

        assertEquals(2, tabelle.reiheSetzen(new int[] {0, 2}, ""));
        assertEquals("", tabelle.getValueAt(2, PlanTabelle.REIHE));
    }

    @Test
    void mehrereZeilenAufEinmalUebernehmenOderNicht() throws IOException {
        Path ziel = quelle.resolve("Sammlung");
        datei("Batman.Begins.2005.German.1080p/bb.mkv");
        datei("Der.Pate.1972.German.1080p/pate.mkv");
        datei("The.Dark.Knight.2008.German.1080p/tdk.mkv");
        PlanTabelle tabelle = new PlanTabelle();
        int[] aenderungen = {0};
        tabelle.beiAenderung(() -> aenderungen[0]++);
        tabelle.anzeigen(Vorschlaege.einlesen(quelle, ziel), quelle);

        tabelle.uebernehmenSetzen(new int[] {0, 2}, false);
        assertEquals(List.of(false, true, false), uebernehmen(tabelle));
        assertEquals("Ausgelassen", tabelle.getValueAt(0, PlanTabelle.STATUS));
        assertEquals(1, aenderungen[0], "einmal neu geplant, nicht je Zeile");

        // Leertaste: nicht alle markierten übernommen, also werden alle übernommen.
        tabelle.uebernehmenUmschalten(new int[] {0, 1});
        assertEquals(List.of(true, true, false), uebernehmen(tabelle));
        // Jetzt sind alle markierten übernommen, also werden alle abgewählt.
        tabelle.uebernehmenUmschalten(new int[] {0, 1});
        assertEquals(List.of(false, false, false), uebernehmen(tabelle));
    }

    private static List<Object> uebernehmen(PlanTabelle tabelle) {
        return List.of(tabelle.getValueAt(0, PlanTabelle.UEBERNEHMEN), tabelle.getValueAt(1, PlanTabelle.UEBERNEHMEN),
                tabelle.getValueAt(2, PlanTabelle.UEBERNEHMEN));
    }

    @Test
    void zusammenfassungZaehltNachArt() {
        Film f = new Film(quelle.resolve("a.mkv"), 1, null, List.of());
        assertEquals("Keine Filme gefunden.", Fenster.zusammenfassung(List.of()));
        assertTrue(Fenster.zusammenfassung(List.of(new Aktion(Art.UNERKANNT, f, List.of(), "")))
                .startsWith("0 werden einsortiert, 0 Doubletten (davon 0 in Quarantäne), "
                        + "0 ersetzen vorhandene, 0 werden in Filmreihen umsortiert, 0 andere Fassungen zu entscheiden, 1 nicht erkannt, "
                        + "0 Konflikte, 0 ausgelassen."));
    }

    private void datei(String pfad) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, pfad);
    }
}
