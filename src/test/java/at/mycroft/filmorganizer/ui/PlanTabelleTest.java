package at.mycroft.filmorganizer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import at.mycroft.filmorganizer.Aktion;
import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Film;
import at.mycroft.filmorganizer.FilmInfo;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTabelleTest {

    private final Path quelle = Path.of("quelle").toAbsolutePath();
    private final Path ziel = quelle.resolve("Sammlung");

    @Test
    void zeigtEinsortierenZuerstUndPfadeRelativ() {
        Film unbekannt = new Film(quelle.resolve("x/irgendwas.mkv"), 1, null, List.of());
        Film kater = new Film(quelle.resolve("Kater 2011/k.mkv"), 1, new FilmInfo("Der gestiefelte Kater", 2011, 0), List.of());
        Path neu = ziel.resolve("Der gestiefelte Kater (2011)/Der gestiefelte Kater (2011).mkv");
        PlanTabelle tabelle = new PlanTabelle();
        tabelle.anzeigen(List.of(
                new Aktion(Art.UNERKANNT, unbekannt, List.of(), "Titel und Jahr nicht erkennbar"),
                new Aktion(Art.EINSORTIEREN, kater, List.of(new Aktion.Verschiebung(kater.video(), neu)), "")),
                quelle, ziel);

        assertEquals(2, tabelle.getRowCount());
        assertEquals("Wird einsortiert", tabelle.getValueAt(0, 0));
        assertEquals("Der gestiefelte Kater (2011)", tabelle.getValueAt(0, 1));
        assertEquals(Path.of("Kater 2011", "k.mkv").toString(), tabelle.getValueAt(0, 2));
        assertEquals(Path.of("Der gestiefelte Kater (2011)", "Der gestiefelte Kater (2011).mkv").toString(),
                tabelle.getValueAt(0, 3));
        assertEquals("Nicht erkannt", tabelle.getValueAt(1, 0));
        assertEquals("Titel und Jahr nicht erkennbar", tabelle.getValueAt(1, 3));
    }

    @Test
    void zusammenfassungZaehltNachArt() {
        Film f = new Film(quelle.resolve("a.mkv"), 1, null, List.of());
        assertEquals("Keine Filme gefunden.", Fenster.zusammenfassung(List.of()));
        assertEquals("0 werden einsortiert, 0 Doubletten, 1 nicht erkannt, 0 Konflikte. Es wurde noch nichts verändert.",
                Fenster.zusammenfassung(List.of(new Aktion(Art.UNERKANNT, f, List.of(), ""))));
    }
}
