package at.mycroft.filmorganizer.ui;

import at.mycroft.filmorganizer.Abgleich;
import at.mycroft.filmorganizer.Abgleich.Fund;
import at.mycroft.filmorganizer.Abgleich.Wahl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.table.AbstractTableModel;

/**
 * Tabellenmodell des Abgleichs zweier Sammlungen: eine Zeile je Fund mit beiden Fassungen und der
 * Entscheidung, welche bleibt. Die Entscheidung ist mit dem Vorschlag vorbelegt und lässt sich je Zeile
 * oder für mehrere markierte Zeilen auf einmal ändern.
 */
final class AbgleichTabelle extends AbstractTableModel {

    static final int STATUS = 0;
    static final int TITEL = 1;
    static final int ERSTE = 2;
    static final int ZWEITE = 3;
    static final int HINWEIS = 4;
    static final int ENTSCHEIDUNG = 5;

    private static final String[] SPALTEN = {"Status", "Titel", "Fassung 1", "Fassung 2", "Vergleich", "Entscheidung"};

    private List<Fund> funde = List.of();
    private final Map<String, Wahl> wahlen = new LinkedHashMap<>();
    private Runnable nachAenderung = () -> { };

    void anzeigen(List<Fund> neueFunde, Map<String, Wahl> gespeichert) {
        funde = List.copyOf(neueFunde);
        wahlen.clear();
        // Nur Entscheidungen zu Funden, die es noch gibt.
        for (Fund f : funde) {
            Wahl w = gespeichert.get(f.kennung());
            if (w != null) {
                wahlen.put(f.kennung(), w);
            }
        }
        fireTableDataChanged();
    }

    /** Wird nach jeder Änderung einer Entscheidung aufgerufen (z.B. zum Speichern). */
    void beiAenderung(Runnable aktion) {
        nachAenderung = aktion;
    }

    List<Fund> funde() {
        return funde;
    }

    /** Die von Hand getroffenen Entscheidungen (Kennung → Wahl). */
    Map<String, Wahl> wahlen() {
        return Map.copyOf(wahlen);
    }

    Wahl wahl(int zeile) {
        return Abgleich.wahl(funde.get(zeile), wahlen);
    }

    /** Setzt die Entscheidung für mehrere Zeilen auf einmal. */
    void wahlSetzen(int[] zeilen, Wahl wahl) {
        for (int zeile : zeilen) {
            Fund f = funde.get(zeile);
            if (wahl == f.vorschlag()) {
                wahlen.remove(f.kennung());
            } else {
                wahlen.put(f.kennung(), wahl);
            }
        }
        fireTableDataChanged();
        nachAenderung.run();
    }

    /** Auswahl für die Spalte Entscheidung, der Vorschlag ist gekennzeichnet. */
    String[] auswahl(int zeile) {
        Fund f = funde.get(zeile);
        List<String> texte = new ArrayList<>();
        if (f.vorschlag() == Wahl.OFFEN) {
            texte.add(text(f, Wahl.OFFEN));
        }
        for (Wahl w : List.of(Wahl.ERSTE_BEHALTEN, Wahl.ZWEITE_BEHALTEN, Wahl.BEIDE_BEHALTEN)) {
            texte.add(text(f, w));
        }
        return texte.toArray(String[]::new);
    }

    private static String text(Fund f, Wahl w) {
        return w == f.vorschlag() && w != Wahl.OFFEN ? w.text() + " (Vorschlag)" : w.text();
    }

    /** Anzahl der Fassungen, die nach den aktuellen Entscheidungen aus Sammlung {@code name} weggehen. */
    long abgaenge(String name) {
        return funde.stream().filter(f -> {
            Wahl w = Abgleich.wahl(f, wahlen);
            return w == Wahl.ERSTE_BEHALTEN && f.zweite().name().equals(name)
                    || w == Wahl.ZWEITE_BEHALTEN && f.erste().name().equals(name);
        }).map(f -> Abgleich.wahl(f, wahlen) == Wahl.ERSTE_BEHALTEN ? f.zweite().video() : f.erste().video())
                .distinct().count();
    }

    @Override
    public int getRowCount() {
        return funde.size();
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
    public boolean isCellEditable(int zeile, int spalte) {
        return spalte == ENTSCHEIDUNG;
    }

    @Override
    public Object getValueAt(int zeile, int spalte) {
        Fund f = funde.get(zeile);
        return switch (spalte) {
            case STATUS -> f.art().text();
            case TITEL -> f.erste().info().titel() + (f.erste().info().jahr() > 0 ? " (" + f.erste().info().jahr() + ")" : "")
                    + (f.erste().info().istFolge() ? " – " + (f.erste().info().istSendung()
                            ? f.erste().info().folgentitel() : f.erste().info().kennung()) : "");
            case ERSTE -> f.erste().anzeige();
            case ZWEITE -> f.zweite().anzeige();
            case HINWEIS -> f.hinweis();
            default -> text(f, Abgleich.wahl(f, wahlen));
        };
    }

    @Override
    public void setValueAt(Object wert, int zeile, int spalte) {
        if (spalte != ENTSCHEIDUNG) {
            return;
        }
        Fund f = funde.get(zeile);
        for (Wahl w : Wahl.values()) {
            if (text(f, w).equals(String.valueOf(wert))) {
                wahlSetzen(new int[] {zeile}, w);
                return;
            }
        }
    }
}
