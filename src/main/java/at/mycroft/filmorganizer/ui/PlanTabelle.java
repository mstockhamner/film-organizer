package at.mycroft.filmorganizer.ui;

import at.mycroft.filmorganizer.Aktion;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import at.mycroft.filmorganizer.Vorschlaege;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
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
    static final int REIHE = 7;

    static final String IN_QUARANTAENE = "Doublette → Quarantäne";
    static final String ERSETZT = "Ersetzt vorhandene";
    static final String UMSORTIERT = "Wird umsortiert";

    private static final String[] SPALTEN = {"Übernehmen", "Status", "Titel", "Jahr", "Gefunden in",
        "Neuer Ort / Hinweis", "Entscheidung", "Filmreihe"};

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
        Aktion aktion = plan.get(zeile);
        // Titel und Jahr nur bei Filmen aus der Quelle; Filme aus der Sammlung behalten ihren Namen.
        boolean ausQuelle = vorschlaege.istGescannt(aktion.film().video());
        return spalte == UEBERNEHMEN || (spalte == TITEL || spalte == JAHR) && ausQuelle
                || spalte == ENTSCHEIDUNG && !aktion.auswahl().isEmpty()
                || spalte == REIHE && mitReihe(aktion, ausQuelle);
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
            case ENTSCHEIDUNG -> aktion.auswahl().isEmpty() ? "" : text(aktion, aktion.entscheidung());
            case REIHE -> aktion.film().erkannt() ? aktion.film().info().reihe() : "";
            default -> aktion.art() == Aktion.Art.EINSORTIEREN || aktion.art() == Aktion.Art.UMSORTIEREN
                    ? relativ(vorschlaege.ziel(), neuerOrt(aktion))
                    : aktion.art() == Aktion.Art.ERSETZEN || aktion.inQuarantaene()
                    ? relativ(vorschlaege.ziel(), neuerOrt(aktion)) + " (" + aktion.hinweis() + ")"
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
            case REIHE -> vorschlaege.reiheSetzen(video, wert == null ? "" : String.valueOf(wert));
            case ENTSCHEIDUNG -> {
                for (Entscheidung e : aktion.auswahl()) {
                    if (text(aktion, e).equals(String.valueOf(wert))) {
                        vorschlaege.entscheiden(video, e);
                    }
                }
            }
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

    /**
     * Setzt die Filmreihe für mehrere Zeilen auf einmal (leer: aus der Reihe nehmen). Serienfolgen und
     * Filme ohne erkannten Titel werden übergangen.
     *
     * @return Anzahl der Filme, deren Reihe gesetzt wurde
     */
    int reiheSetzen(int[] zeilen, String reihe) {
        int gesetzt = 0;
        for (int zeile : zeilen) {
            Aktion aktion = plan.get(zeile);
            if (aktion.film().erkannt() && !aktion.film().info().istFolge()) {
                vorschlaege.reiheSetzen(aktion.film().video(), reihe);
                gesetzt++;
            }
        }
        if (gesetzt > 0) {
            try {
                plan = vorschlaege.plan();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            fireTableDataChanged();
            nachAenderung.run();
        }
        return gesetzt;
    }

    /** Quelle, aus der die angezeigten Filme eingelesen wurden. */
    Path quelle() {
        return quelle;
    }

    Vorschlaege vorschlaege() {
        return vorschlaege;
    }

    /** Plant nach Änderungen außerhalb der Tabelle (z.B. im Fenster "Filmreihen verwalten") neu. */
    void aktualisieren() {
        try {
            plan = vorschlaege.plan();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        fireTableDataChanged();
        nachAenderung.run();
    }

    /** Merkt die von Hand gesetzten Filmreihen dauerhaft, nachdem {@code plan} ausgeführt wurde. */
    void zuordnungenMerken(List<Aktion> ausgefuehrt) throws IOException {
        if (vorschlaege != null) {
            vorschlaege.zuordnungenMerken(ausgefuehrt);
        }
    }

    /** Alle Filmreihen aus Vorschau und Sammlung, alphabetisch, für die Auswahl beim Zuweisen. */
    List<String> bekannteReihen() {
        TreeSet<String> reihen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (vorschlaege != null) {
            reihen.addAll(vorschlaege.sammlungsReihen());
        }
        plan.stream().filter(a -> a.film().erkannt()).map(a -> a.film().info().reihe())
                .filter(r -> !r.isEmpty()).forEach(reihen::add);
        return List.copyOf(reihen);
    }

    /** Auswahl für die Spalte Entscheidung in dieser Zeile, der Vorschlag ist gekennzeichnet. */
    String[] auswahl(int zeile) {
        Aktion aktion = plan.get(zeile);
        return aktion.auswahl().stream().map(e -> text(aktion, e)).toArray(String[]::new);
    }

    private static String text(Aktion aktion, Entscheidung e) {
        return e == aktion.vorschlag() && e != Entscheidung.OFFEN ? e.text() + " (Vorschlag)" : e.text();
    }

    /** Wohin das Video kommt: in die Sammlung oder in die Quarantäne. */
    private static Path neuerOrt(Aktion aktion) {
        return aktion.verschiebungen().stream().filter(v -> v.von().equals(aktion.film().video()))
                .findFirst().orElse(aktion.verschiebungen().get(0)).nach();
    }

    /** Die Filmreihe lässt sich bei Filmen ändern, die einsortiert oder in der Sammlung umsortiert werden. */
    private static boolean mitReihe(Aktion aktion, boolean ausQuelle) {
        return aktion.film().erkannt() && !aktion.film().info().istFolge()
                && (aktion.art() == Aktion.Art.EINSORTIEREN || !ausQuelle);
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
            case ERSETZEN -> ERSETZT;
            case UMSORTIEREN -> UMSORTIERT;
            case FASSUNG -> "Andere Fassung";
            case UNERKANNT -> "Nicht erkannt";
            case KONFLIKT -> "Konflikt";
            case AUSGELASSEN -> "Ausgelassen";
        };
    }
}
