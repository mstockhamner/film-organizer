package at.mycroft.filmorganizer.ui;

import at.mycroft.filmorganizer.Aktion;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/**
 * Tabellenmodell für die Vorschau: eine Zeile pro gefundenem Film.
 */
final class PlanTabelle extends AbstractTableModel {

    private static final String[] SPALTEN = {"Status", "Film", "Gefunden in", "Neuer Ort / Hinweis"};

    private List<Aktion> plan = List.of();
    private Path quelle;
    private Path ziel;

    /** Zeigt den Plan an, Einsortieren zuerst, dann Doubletten, nicht erkannte Filme und Konflikte. */
    void anzeigen(List<Aktion> neuerPlan, Path neueQuelle, Path neuesZiel) {
        plan = neuerPlan.stream().sorted(Comparator.comparing(Aktion::art)).toList();
        quelle = neueQuelle;
        ziel = neuesZiel;
        fireTableDataChanged();
    }

    List<Aktion> plan() {
        return plan;
    }

    Aktion zeile(int index) {
        return plan.get(index);
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
    public Object getValueAt(int index, int spalte) {
        Aktion aktion = plan.get(index);
        return switch (spalte) {
            case 0 -> status(aktion.art());
            case 1 -> aktion.film().erkannt() ? aktion.film().info().zielName() : "?";
            case 2 -> relativ(quelle, aktion.film().video());
            default -> aktion.art() == Aktion.Art.EINSORTIEREN
                    ? relativ(ziel, aktion.verschiebungen().get(0).nach())
                    : aktion.hinweis();
        };
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
            case UNERKANNT -> "Nicht erkannt";
            case KONFLIKT -> "Konflikt";
        };
    }
}
