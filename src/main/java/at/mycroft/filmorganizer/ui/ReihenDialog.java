package at.mycroft.filmorganizer.ui;

import at.mycroft.filmorganizer.Vorschlaege;
import at.mycroft.filmorganizer.Vorschlaege.Sammlungsfilm;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

/**
 * Fenster "Filmreihen verwalten": zeigt die Filmreihen der Sammlung mit ihren Filmen. Reihen lassen sich
 * umbenennen und auflösen, Filme in eine andere Reihe verschieben oder aus ihrer Reihe nehmen. Alles
 * landet als Vorschlag in der Vorschau ("Wird umsortiert") und wird erst mit "Einsortieren" ausgeführt.
 */
final class ReihenDialog extends JDialog {

    static final String OHNE_REIHE = "(ohne Filmreihe)";

    private final Vorschlaege vorschlaege;
    private final Runnable nachAenderung;
    private final DefaultListModel<String> reihen = new DefaultListModel<>();
    private final DefaultListModel<Sammlungsfilm> filme = new DefaultListModel<>();
    private final JList<String> reihenListe = new JList<>(reihen);
    private final JList<Sammlungsfilm> filmListe = new JList<>(filme);
    private Map<String, List<Sammlungsfilm>> nachReihe = Map.of();

    ReihenDialog(JFrame besitzer, Vorschlaege vorschlaege, Runnable nachAenderung) {
        super(besitzer, "Filmreihen verwalten", true);
        this.vorschlaege = vorschlaege;
        this.nachAenderung = nachAenderung;
        setLayout(new BorderLayout(8, 8));

        reihenListe.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        reihenListe.addListSelectionListener(e -> filmeZeigen());
        filmListe.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> liste, Object wert, int index, boolean ausgewaehlt,
                    boolean fokus) {
                Sammlungsfilm s = (Sammlungsfilm) wert;
                String text = s.info().titel() + " (" + s.info().jahr() + ")";
                if (!s.reihe().equalsIgnoreCase(s.info().reihe())) {
                    text += s.reihe().isEmpty() ? "  → ohne Reihe" : "  → " + s.reihe();
                }
                return super.getListCellRendererComponent(liste, text, index, ausgewaehlt, fokus);
            }
        });

        JPanel listen = new JPanel(new GridLayout(1, 2, 8, 0));
        listen.add(mitTitel("Filmreihen", new JScrollPane(reihenListe)));
        listen.add(mitTitel("Filme (Strg-/Umschalt-Klick für mehrere)", new JScrollPane(filmListe)));
        listen.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        add(listen, BorderLayout.CENTER);

        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.LEFT));
        knoepfe.add(knopf("Reihe umbenennen …", this::umbenennen));
        knoepfe.add(knopf("Reihe auflösen", this::aufloesen));
        knoepfe.add(knopf("Filme in Reihe …", this::filmeZuweisen));
        knoepfe.add(knopf("Filme aus Reihe nehmen", () -> filmeSetzen("")));
        JButton schliessen = knopf("Schließen", this::dispose);
        knoepfe.add(schliessen);
        JPanel unten = new JPanel(new BorderLayout());
        unten.add(knoepfe, BorderLayout.NORTH);
        unten.add(new JLabel("  Änderungen erscheinen in der Vorschau als „Wird umsortiert“ und werden erst mit "
                + "„Einsortieren …“ ausgeführt."), BorderLayout.SOUTH);
        unten.setBorder(BorderFactory.createEmptyBorder(0, 6, 10, 10));
        add(unten, BorderLayout.SOUTH);

        neuLaden(null);
        setSize(820, 480);
        setLocationRelativeTo(besitzer);
    }

    /** Filme der Sammlung nach gewünschter Reihe, Reihen alphabetisch, "ohne Reihe" zuerst. */
    static Map<String, List<Sammlungsfilm>> gruppieren(List<Sammlungsfilm> sammlung) {
        Map<String, List<Sammlungsfilm>> gruppen = new TreeMap<>((a, b) -> a.equals(b) ? 0
                : a.equals(OHNE_REIHE) ? -1 : b.equals(OHNE_REIHE) ? 1 : a.compareToIgnoreCase(b));
        for (Sammlungsfilm s : sammlung) {
            String name = s.reihe().isEmpty() ? OHNE_REIHE : s.reihe();
            gruppen.computeIfAbsent(name, k -> new ArrayList<>()).add(s);
        }
        return gruppen;
    }

    private void neuLaden(String auswahl) {
        try {
            nachReihe = gruppieren(vorschlaege.sammlung());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        reihen.clear();
        nachReihe.keySet().forEach(reihen::addElement);
        String waehlen = auswahl != null && nachReihe.containsKey(auswahl) ? auswahl
                : reihen.isEmpty() ? null : reihen.get(0);
        reihenListe.setSelectedValue(waehlen, true);
        filmeZeigen();
    }

    private void filmeZeigen() {
        filme.clear();
        String reihe = reihenListe.getSelectedValue();
        if (reihe != null) {
            nachReihe.getOrDefault(reihe, List.of()).forEach(filme::addElement);
        }
    }

    private void umbenennen() {
        String reihe = gewaehlteReihe();
        if (reihe == null) {
            return;
        }
        String neu = reiheAbfragen("Neuer Name für die Filmreihe „" + reihe + "“:", reihe);
        if (neu != null && !neu.isEmpty()) {
            aendern(() -> vorschlaege.reiheUmbenennen(reihe, neu), neu);
        }
    }

    private void aufloesen() {
        String reihe = gewaehlteReihe();
        if (reihe != null && JOptionPane.showConfirmDialog(this, "Die Filmreihe „" + reihe + "“ auflösen?\n"
                + "Ihre Filme kommen direkt ins Ziel.", "Reihe auflösen", JOptionPane.OK_CANCEL_OPTION)
                == JOptionPane.OK_OPTION) {
            aendern(() -> vorschlaege.reiheUmbenennen(reihe, ""), OHNE_REIHE);
        }
    }

    private void filmeZuweisen() {
        if (gewaehlteFilme().isEmpty()) {
            return;
        }
        String neu = reiheAbfragen(gewaehlteFilme().size() + " markierte Filme in diese Filmreihe aufnehmen:", "");
        if (neu != null) {
            filmeSetzen(neu);
        }
    }

    private void filmeSetzen(String reihe) {
        List<Sammlungsfilm> markiert = gewaehlteFilme();
        if (!markiert.isEmpty()) {
            aendern(() -> markiert.forEach(s -> vorschlaege.reiheSetzen(s.video(), reihe)), reihenListe.getSelectedValue());
        }
    }

    private interface Aenderung {
        void ausfuehren() throws IOException;
    }

    private void aendern(Aenderung aenderung, String danachAuswaehlen) {
        try {
            aenderung.ausfuehren();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        nachAenderung.run();
        neuLaden(danachAuswaehlen);
    }

    private String gewaehlteReihe() {
        String reihe = reihenListe.getSelectedValue();
        if (reihe == null || reihe.equals(OHNE_REIHE)) {
            JOptionPane.showMessageDialog(this, "Bitte links eine Filmreihe wählen.", "Filmreihen",
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return reihe;
    }

    private List<Sammlungsfilm> gewaehlteFilme() {
        List<Sammlungsfilm> markiert = filmListe.getSelectedValuesList();
        if (markiert.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Bitte rechts einen oder mehrere Filme markieren.", "Filmreihen",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        return markiert;
    }

    /** Fragt nach einem Reihennamen mit Auswahl der vorhandenen; {@code null} bei Abbruch. */
    private String reiheAbfragen(String frage, String vorbelegt) {
        JComboBox<String> auswahl = new JComboBox<>(nachReihe.keySet().stream()
                .filter(r -> !r.equals(OHNE_REIHE)).toArray(String[]::new));
        auswahl.setEditable(true);
        auswahl.setSelectedItem(vorbelegt);
        int antwort = JOptionPane.showConfirmDialog(this, new Object[] {frage, auswahl}, "Filmreihe",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        Object wert = auswahl.getEditor().getItem();
        return antwort == JOptionPane.OK_OPTION ? (wert == null ? "" : wert.toString().trim()) : null;
    }

    private static JPanel mitTitel(String titel, Component inhalt) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(new JLabel(titel), BorderLayout.NORTH);
        p.add(inhalt, BorderLayout.CENTER);
        return p;
    }

    private static JButton knopf(String text, Runnable aktion) {
        JButton b = new JButton(text);
        b.addActionListener(e -> aktion.run());
        return b;
    }
}
