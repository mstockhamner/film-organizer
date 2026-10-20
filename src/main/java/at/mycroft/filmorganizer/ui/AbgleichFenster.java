package at.mycroft.filmorganizer.ui;

import at.mycroft.filmorganizer.Abgleich;
import at.mycroft.filmorganizer.Abgleich.Fund;
import at.mycroft.filmorganizer.Abgleich.Wahl;
import at.mycroft.filmorganizer.Log;
import at.mycroft.filmorganizer.Vorschlaege;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Fenster "Sammlungen abgleichen": zwei schon organisierte Sammlungen auf Doubletten prüfen, je Fund
 * entscheiden, welche Fassung bleibt, und die anderen in die Quarantäne ihrer Sammlung verschieben.
 * Entscheidungen werden sofort gespeichert und beim nächsten Abgleich derselben Sammlungen wiederhergestellt.
 */
final class AbgleichFenster extends JFrame {

    private static final Logger LOG = Log.fuer(AbgleichFenster.class);
    private static final Preferences EINSTELLUNGEN = Preferences.userNodeForPackage(AbgleichFenster.class);

    private final JTextField sammlungA;
    private final JTextField sammlungB = new JTextField(EINSTELLUNGEN.get("abgleichB", ""), 40);
    private final JCheckBox inSich = new JCheckBox("auch jede Sammlung in sich prüfen", EINSTELLUNGEN.getBoolean("inSich", true));
    private final JButton abgleichen = new JButton("Abgleichen");
    private final JButton ausfuehren = new JButton("Ausführen …");
    private final JButton rueckgaengig = new JButton("Letzten Abgleich rückgängig …");
    private final JLabel status = new JLabel("Sammlung A (Hauptsammlung) und B wählen, dann „Abgleichen“.");
    private final AbgleichTabelle tabelle = new AbgleichTabelle();
    private final JTable ansicht = new JTable(tabelle);
    private Path a;
    private Path b;

    AbgleichFenster(String vorschlagA) {
        super("Filmorganizer – Sammlungen abgleichen");
        sammlungA = new JTextField(EINSTELLUNGEN.get("abgleichA", vorschlagA), 40);
        setLayout(new BorderLayout(8, 8));
        add(oben(), BorderLayout.NORTH);
        add(tabellenBereich(), BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        add(status, BorderLayout.SOUTH);

        abgleichen.addActionListener(e -> abgleichen());
        ausfuehren.addActionListener(e -> ausfuehren());
        rueckgaengig.addActionListener(e -> rueckgaengigMachen());
        ausfuehren.setEnabled(false);
        tabelle.beiAenderung(this::nachAenderung);
        setSize(1200, 600);
        setLocationRelativeTo(null);
    }

    private JPanel oben() {
        JPanel bereich = new JPanel(new GridBagLayout());
        bereich.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        zeile(bereich, 0, "Sammlung A (Hauptsammlung):", sammlungA);
        zeile(bereich, 1, "Sammlung B:", sammlungB);
        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        knoepfe.add(inSich);
        knoepfe.add(abgleichen);
        knoepfe.add(ausfuehren);
        knoepfe.add(rueckgaengig);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = 2;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(8, 0, 0, 0);
        bereich.add(knoepfe, c);
        return bereich;
    }

    private void zeile(JPanel bereich, int zeile, String beschriftung, JTextField feld) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = zeile;
        c.insets = new Insets(2, 0, 2, 8);
        c.anchor = GridBagConstraints.WEST;
        bereich.add(new JLabel(beschriftung), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        bereich.add(feld, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton waehlen = new JButton("Auswählen …");
        waehlen.addActionListener(e -> {
            JFileChooser auswahl = new JFileChooser(feld.getText());
            auswahl.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (auswahl.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                feld.setText(auswahl.getSelectedFile().getPath());
            }
        });
        bereich.add(waehlen, c);
    }

    private JScrollPane tabellenBereich() {
        ansicht.setAutoCreateRowSorter(true);
        ansicht.setFillsViewportHeight(true);
        ansicht.setRowHeight(ansicht.getRowHeight() + 4);
        ansicht.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        int[] breiten = {90, 220, 300, 300, 260, 150};
        for (int spalte = 0; spalte < breiten.length; spalte++) {
            ansicht.getColumnModel().getColumn(spalte).setPreferredWidth(breiten[spalte]);
        }
        JComboBox<String> auswahl = new JComboBox<>();
        ansicht.getColumnModel().getColumn(AbgleichTabelle.ENTSCHEIDUNG).setCellEditor(new DefaultCellEditor(auswahl) {
            @Override
            public Component getTableCellEditorComponent(JTable table, Object wert, boolean ausgewaehlt, int zeile, int spalte) {
                auswahl.setModel(new DefaultComboBoxModel<>(tabelle.auswahl(table.convertRowIndexToModel(zeile))));
                return super.getTableCellEditorComponent(table, wert, ausgewaehlt, zeile, spalte);
            }
        });
        DefaultTableCellRenderer mitTooltip = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object wert, boolean ausgewaehlt, boolean fokus,
                    int zeile, int spalte) {
                JComponent c = (JComponent) super.getTableCellRendererComponent(table, wert, ausgewaehlt, fokus, zeile, spalte);
                c.setToolTipText(wert == null ? null : String.valueOf(wert));
                return c;
            }
        };
        for (int spalte : new int[] {AbgleichTabelle.TITEL, AbgleichTabelle.ERSTE, AbgleichTabelle.ZWEITE,
            AbgleichTabelle.HINWEIS}) {
            ansicht.getColumnModel().getColumn(spalte).setCellRenderer(mitTooltip);
        }
        JPopupMenu menue = new JPopupMenu();
        for (Wahl w : List.of(Wahl.ERSTE_BEHALTEN, Wahl.ZWEITE_BEHALTEN, Wahl.BEIDE_BEHALTEN)) {
            JMenuItem eintrag = new JMenuItem("Markierte: " + w.text());
            eintrag.addActionListener(e -> wahlFuerMarkierte(w));
            menue.add(eintrag);
        }
        ansicht.setComponentPopupMenu(menue);
        JScrollPane rahmen = new JScrollPane(ansicht);
        rahmen.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10), rahmen.getBorder()));
        return rahmen;
    }

    private void wahlFuerMarkierte(Wahl wahl) {
        int[] zeilen = Arrays.stream(ansicht.getSelectedRows()).map(ansicht::convertRowIndexToModel).toArray();
        if (zeilen.length == 0) {
            JOptionPane.showMessageDialog(this, "Bitte zuerst Zeilen markieren (Strg- oder Umschalt-Klick).",
                    "Abgleich", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        tabelle.wahlSetzen(zeilen, wahl);
        ansicht.clearSelection();
        for (int zeile : zeilen) {
            int sicht = ansicht.convertRowIndexToView(zeile);
            ansicht.addRowSelectionInterval(sicht, sicht);
        }
    }

    private void abgleichen() {
        if (sammlungA.getText().isBlank() || sammlungB.getText().isBlank()) {
            fehler("Bitte beide Sammlungen wählen.");
            return;
        }
        Path neuA = Path.of(sammlungA.getText().trim());
        Path neuB = Path.of(sammlungB.getText().trim());
        boolean auchInSich = inSich.isSelected();
        EINSTELLUNGEN.put("abgleichA", neuA.toString());
        EINSTELLUNGEN.put("abgleichB", neuB.toString());
        EINSTELLUNGEN.putBoolean("inSich", auchInSich);
        imHintergrund("Gleiche " + neuA + " und " + neuB + " ab …",
                () -> new Ergebnis(Abgleich.abgleichen(neuA, neuB, auchInSich),
                        Abgleich.wahlenLaden(Vorschlaege.standardSpeicherordner(), neuA, neuB)),
                ergebnis -> {
                    a = neuA;
                    b = neuB;
                    tabelle.anzeigen(ergebnis.funde(), ergebnis.wahlen());
                    nachAenderung();
                });
    }

    private record Ergebnis(List<Fund> funde, Map<String, Wahl> wahlen) {
    }

    /** Speichert die Entscheidungen und zeigt die Zusammenfassung. */
    private void nachAenderung() {
        if (a != null) {
            try {
                Abgleich.wahlenSpeichern(Vorschlaege.standardSpeicherordner(), a, b, tabelle.wahlen());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        long ausA = tabelle.abgaenge("A");
        long ausB = tabelle.abgaenge("B");
        long offen = tabelle.funde().stream().filter(f -> Abgleich.wahl(f, tabelle.wahlen()) == Wahl.OFFEN).count();
        ausfuehren.setEnabled(ausA + ausB > 0);
        status.setText(tabelle.funde().isEmpty() ? "Keine Doubletten gefunden."
                : tabelle.funde().size() + " Funde: " + ausA + " Fassungen aus A und " + ausB
                        + " aus B kämen in die Quarantäne, " + offen + " noch zu entscheiden. "
                        + "Entscheidungen werden automatisch gespeichert. Es wurde noch nichts verschoben.");
    }

    private void ausfuehren() {
        long ausA = tabelle.abgaenge("A");
        long ausB = tabelle.abgaenge("B");
        int antwort = JOptionPane.showConfirmDialog(this, ausA + " Fassungen aus A kommen nach " + a.resolve("_Doubletten")
                + ",\n" + ausB + " Fassungen aus B nach " + b.resolve("_Doubletten") + ".\n\n"
                + "Unentschiedene Funde und „Beide behalten“ bleiben unverändert.\n"
                + "Der Abgleich lässt sich danach rückgängig machen.", "Abgleich ausführen",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (antwort != JOptionPane.OK_OPTION) {
            return;
        }
        List<Fund> funde = tabelle.funde();
        Map<String, Wahl> wahlen = tabelle.wahlen();
        ausfuehren.setEnabled(false);
        imHintergrund("Verschiebe in die Quarantäne …",
                () -> Abgleich.ausfuehren(funde, wahlen, new PrintStream(OutputStream.nullOutputStream())),
                anzahl -> {
                    JOptionPane.showMessageDialog(this, anzahl + " Fassungen in die Quarantäne verschoben.", "Fertig",
                            JOptionPane.INFORMATION_MESSAGE);
                    abgleichen();
                });
    }

    private void rueckgaengigMachen() {
        if (sammlungA.getText().isBlank() || sammlungB.getText().isBlank()) {
            fehler("Bitte beide Sammlungen wählen.");
            return;
        }
        Path ra = Path.of(sammlungA.getText().trim());
        Path rb = Path.of(sammlungB.getText().trim());
        if (JOptionPane.showConfirmDialog(this, "Den letzten Abgleich von\n" + ra + "\nund\n" + rb + "\nrückgängig machen?",
                "Rückgängig", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        ByteArrayOutputStream meldungen = new ByteArrayOutputStream();
        imHintergrund("Mache den letzten Abgleich rückgängig …",
                () -> Abgleich.rueckgaengig(ra, rb, new PrintStream(meldungen, true, StandardCharsets.UTF_8)),
                zurueck -> {
                    String text = zurueck < 0 ? "Es gibt keinen Abgleich, der rückgängig gemacht werden kann."
                            : zurueck + " Dateien an ihren alten Platz zurückverschoben.";
                    String hinweise = meldungen.toString(StandardCharsets.UTF_8).trim();
                    JOptionPane.showMessageDialog(this, hinweise.isEmpty() ? text : text + "\n\n" + hinweise,
                            "Rückgängig", JOptionPane.INFORMATION_MESSAGE);
                    abgleichen();
                });
    }

    private <T> void imHintergrund(String meldung, Callable<T> arbeit, Consumer<T> danach) {
        abgleichen.setEnabled(false);
        rueckgaengig.setEnabled(false);
        status.setText(meldung);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return arbeit.call();
            }

            @Override
            protected void done() {
                abgleichen.setEnabled(true);
                rueckgaengig.setEnabled(true);
                try {
                    danach.accept(get());
                } catch (ExecutionException e) {
                    fehlerAnzeigen(meldung, e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    fehlerAnzeigen(meldung, e);
                }
            }
        }.execute();
    }

    private void fehlerAnzeigen(String beiAktion, Throwable fehler) {
        LOG.log(Level.SEVERE, "Fehler bei: " + beiAktion, fehler);
        status.setText("Fehler: " + Log.beschreibung(fehler));
        fehler(Log.beschreibung(fehler) + "\n\nDetails stehen im Logfile:\n" + Log.datei());
    }

    private void fehler(String text) {
        JOptionPane.showMessageDialog(this, text, "Filmorganizer", JOptionPane.WARNING_MESSAGE);
    }
}
