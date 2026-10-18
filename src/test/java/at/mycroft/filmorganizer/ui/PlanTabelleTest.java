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
        assertEquals("Nicht erkannt", tabelle.getValueAt(1, PlanTabelle.STATUS));
        assertEquals("Titel und Jahr in der Tabelle eintragen", tabelle.getValueAt(1, PlanTabelle.NEUER_ORT));
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
    void zusammenfassungZaehltNachArt() {
        Film f = new Film(quelle.resolve("a.mkv"), 1, null, List.of());
        assertEquals("Keine Filme gefunden.", Fenster.zusammenfassung(List.of()));
        assertTrue(Fenster.zusammenfassung(List.of(new Aktion(Art.UNERKANNT, f, List.of(), "")))
                .startsWith("0 werden einsortiert, 0 Doubletten, 1 nicht erkannt, 0 Konflikte, 0 ausgelassen."));
    }

    private void datei(String pfad) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, pfad);
    }
}
