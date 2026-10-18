package at.mycroft.filmorganizer.ui;

import at.mycroft.filmorganizer.Aktion;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import at.mycroft.filmorganizer.Vorschlaege;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/**
 * Tabellenmodell der Vorschau: eine Zeile pro eingelesenem Film. Titel und Jahr lassen sich
 * direkt in der Tabelle ändern, über "Übernehmen" wird ein Film an- oder abgewählt. Nach jeder
 * Änderung wird neu geplant, damit Zielordner und Doubletten sofort stimmen.
 */
final class PlanTabelle extends AbstractTableModel {

    static final int UEBERNEHMEN = 0;
    static final int STATUS = 1;
    static final int TITEL = 2;
    static final int JAHR = 3;
    static final int GEFUNDEN = 4;
    static final int NEUER_ORT = 5;
    static final int ENTSCHEIDUNG = 6;

    static final String IN_QUARANTAENE = "Doublette → Quarantäne";

    private static final String[] SPALTEN = {"Übernehmen", "Status", "Titel", "Jahr", "Gefunden in",
        "Neuer Ort / Hinweis", "Entscheidung"};

    /** Auswahl in der Spalte Entscheidung, in der Reihenfolge von {@link Entscheidung}. */
    static final String[] ENTSCHEIDUNGEN = {"bitte wählen", "Beide behalten", "In Quarantäne"};

    private Vorschlaege vorschlaege;
    private List<Aktion> plan = List.of();
    private Path quelle;
    private Runnable nachAenderung = () -> { };

    void anzeigen(Vorschlaege neueVorschlaege, Path neueQuelle) throws IOException {
        vorschlaege = neueVorschlaege;
        quelle = neueQuelle;
        plan = neueVorschlaege.plan();
        fireTableDataChanged();
    }

    void leeren() {
        vorschlaege = null;
        plan = List.of();
        fireTableDataChanged();
    }

    /** Wird nach jeder Änderung in der Tabelle aufgerufen. */
    void beiAenderung(Runnable aktion) {
        nachAenderung = aktion;
    }

    List<Aktion> plan() {
        return plan;
    }

    Path ziel() {
        return vorschlaege == null ? null : vorschlaege.ziel();
    }

    @Override
    public int getRowCount() {
        return plan.size();
    }

    @Override
    public int getColumnCount() {
        return SPALTEN.length;
    }

    @Override
    public String getColumnName(int spalte) {
        return SPALTEN[spalte];
    }

    @Override
    public Class<?> getColumnClass(int spalte) {
        return switch (spalte) {
            case UEBERNEHMEN -> Boolean.class;
            case JAHR -> Integer.class;
            default -> String.class;
        };
    }

    @Override
    public boolean isCellEditable(int zeile, int spalte) {
        return spalte == UEBERNEHMEN || spalte == TITEL || spalte == JAHR
                || spalte == ENTSCHEIDUNG && mitEntscheidung(plan.get(zeile));
    }

    @Override
    public Object getValueAt(int index, int spalte) {
        Aktion aktion = plan.get(index);
        return switch (spalte) {
            case UEBERNEHMEN -> !vorschlaege.istAusgelassen(aktion.film().video());
            case STATUS -> aktion.inQuarantaene() ? IN_QUARANTAENE : status(aktion.art());
            case TITEL -> vorschlaege.titel(aktion.film());
            case JAHR -> {
                int jahr = vorschlaege.jahr(aktion.film());
                yield jahr > 0 ? jahr : null;
            }
            case GEFUNDEN -> relativ(quelle, aktion.film().video());
            case ENTSCHEIDUNG -> mitEntscheidung(aktion)
                    ? ENTSCHEIDUNGEN[vorschlaege.entscheidung(aktion.film().video()).ordinal()] : "";
            default -> aktion.art() == Aktion.Art.EINSORTIEREN
                    ? relativ(vorschlaege.ziel(), aktion.verschiebungen().get(0).nach())
                    : aktion.inQuarantaene()
                    ? relativ(vorschlaege.ziel(), aktion.verschiebungen().get(0).nach()) + " (" + aktion.hinweis() + ")"
                    : hinweis(aktion);
        };
    }

    @Override
    public void setValueAt(Object wert, int index, int spalte) {
        Path video = plan.get(index).film().video();
        Aktion aktion = plan.get(index);
        switch (spalte) {
            case UEBERNEHMEN -> vorschlaege.auslassen(video, !Boolean.TRUE.equals(wert));
            case TITEL -> vorschlaege.korrigieren(video, String.valueOf(wert), vorschlaege.jahr(aktion.film()));
            case JAHR -> vorschlaege.korrigieren(video, vorschlaege.titel(aktion.film()),
                    wert instanceof Integer jahr ? jahr : 0);
            case ENTSCHEIDUNG -> vorschlaege.entscheiden(video,
                    Entscheidung.values()[Math.max(0, List.of(ENTSCHEIDUNGEN).indexOf(String.valueOf(wert)))]);
            default -> {
                return;
            }
        }
        try {
            plan = vorschlaege.plan();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        fireTableDataChanged();
        nachAenderung.run();
    }

    /** Andere Fassung, für die nachgefragt wird, oder eine schon getroffene Entscheidung dazu. */
    private boolean mitEntscheidung(Aktion aktion) {
        return aktion.art() == Aktion.Art.FASSUNG
                || vorschlaege.entscheidung(aktion.film().video()) != Entscheidung.OFFEN;
    }

    private String hinweis(Aktion aktion) {
        if (aktion.art() == Aktion.Art.UNERKANNT) {
            return vorschlaege.titel(aktion.film()).isEmpty() || vorschlaege.jahr(aktion.film()) == 0
                    ? "Titel und Jahr in der Tabelle eintragen"
                    : aktion.hinweis();
        }
        return aktion.hinweis();
    }

    private static String relativ(Path basis, Path pfad) {
        Path a = pfad.toAbsolutePath().normalize();
        Path b = basis.toAbsolutePath().normalize();
        return a.startsWith(b) ? b.relativize(a).toString() : pfad.toString();
    }

    static String status(Aktion.Art art) {
        return switch (art) {
            case EINSORTIEREN -> "Wird einsortiert";
            case DOUBLETTE -> "Doublette";
            case FASSUNG -> "Andere Fassung";
            case UNERKANNT -> "Nicht erkannt";
            case KONFLIKT -> "Konflikt";
            case AUSGELASSEN -> "Ausgelassen";
        };
    }
}
