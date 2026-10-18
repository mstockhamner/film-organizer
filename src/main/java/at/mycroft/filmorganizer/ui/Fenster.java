package at.mycroft.filmorganizer.ui;

import at.mycroft.filmorganizer.Aktion;
import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Ausfuehrung;
import at.mycroft.filmorganizer.Log;
import at.mycroft.filmorganizer.Planer;
import at.mycroft.filmorganizer.Vorschlaege;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Hauptfenster: Ordner wählen, Vorschau ansehen, einsortieren und rückgängig machen.
 * Alles läuft lokal, die zuletzt gewählten Ordner werden nur auf diesem Rechner gemerkt.
 */
public final class Fenster extends JFrame {

    private static final Logger LOG = Log.fuer(Fenster.class);
    private static final Preferences EINSTELLUNGEN = Preferences.userNodeForPackage(Fenster.class);

    private final JTextField quelle = new JTextField(EINSTELLUNGEN.get("quelle", ""), 40);
    private final JTextField ziel = new JTextField(EINSTELLUNGEN.get("ziel", ""), 40);
    private final JButton vorschau = new JButton("Titel einlesen");
    private final JButton einsortieren = new JButton("Einsortieren …");
    private final JButton rueckgaengig = new JButton("Letzten Lauf rückgängig …");
    private final JLabel status = new JLabel("Quelle und Ziel wählen, dann „Titel einlesen“.");
    private final JProgressBar fortschritt = new JProgressBar();
    private final PlanTabelle tabelle = new PlanTabelle();

    public Fenster() {
        super("Filmorganizer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(ordnerBereich(), BorderLayout.NORTH);
        add(tabellenBereich(), BorderLayout.CENTER);
        add(statusBereich(), BorderLayout.SOUTH);

        vorschau.addActionListener(e -> vorschauErstellen());
        einsortieren.addActionListener(e -> einsortieren());
        rueckgaengig.addActionListener(e -> rueckgaengigMachen());
        einsortieren.setEnabled(false);
        tabelle.beiAenderung(this::nachAenderung);
        Log.beiUnerwartetemFehler(fehler -> SwingUtilities.invokeLater(() -> fehlerAnzeigen("Unerwarteter Fehler", fehler)));

        setSize(1100, 650);
        setLocationRelativeTo(null);
    }

    /** Startet die Oberfläche. */
    public static void starten() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
                // Standard-Aussehen verwenden.
            }
            new Fenster().setVisible(true);
        });
    }

    private JPanel ordnerBereich() {
        JPanel bereich = new JPanel(new GridBagLayout());
        bereich.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        ordnerZeile(bereich, 0, "Quelle (unsortierte Filme):", quelle);
        ordnerZeile(bereich, 1, "Ziel (neue Sammlung):", ziel);

        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        knoepfe.add(vorschau);
        knoepfe.add(Box.createHorizontalStrut(8));
        knoepfe.add(einsortieren);
        knoepfe.add(Box.createHorizontalStrut(24));
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

    private void ordnerZeile(JPanel bereich, int zeile, String beschriftung, JTextField feld) {
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
        c.insets = new Insets(2, 0, 2, 0);
        JButton waehlen = new JButton("Auswählen …");
        waehlen.addActionListener(e -> ordnerWaehlen(feld));
        bereich.add(waehlen, c);
    }

    private JScrollPane tabellenBereich() {
        JTable ansicht = new JTable(tabelle);
        ansicht.setAutoCreateRowSorter(true);
        ansicht.setFillsViewportHeight(true);
        ansicht.setRowHeight(ansicht.getRowHeight() + 4);
        ansicht.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        int[] breiten = {80, 110, 240, 60, 330, 330};
        for (int spalte = 0; spalte < breiten.length; spalte++) {
            ansicht.getColumnModel().getColumn(spalte).setPreferredWidth(breiten[spalte]);
        }
        ansicht.getColumnModel().getColumn(PlanTabelle.STATUS).setCellRenderer(new StatusDarstellung());
        DefaultTableCellRenderer mitTooltip = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object wert, boolean ausgewaehlt,
                    boolean fokus, int zeile, int spalte) {
                JComponent c = (JComponent) super.getTableCellRendererComponent(
                        table, wert, ausgewaehlt, fokus, zeile, spalte);
                c.setToolTipText(wert == null ? null : String.valueOf(wert));
                return c;
            }
        };
        for (int spalte : new int[] {PlanTabelle.TITEL, PlanTabelle.GEFUNDEN, PlanTabelle.NEUER_ORT}) {
            ansicht.getColumnModel().getColumn(spalte).setCellRenderer(mitTooltip);
        }
        JScrollPane rahmen = new JScrollPane(ansicht);
        rahmen.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 10, 0, 10), rahmen.getBorder()));
        return rahmen;
    }

    private JPanel statusBereich() {
        JPanel bereich = new JPanel(new BorderLayout(8, 0));
        bereich.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        bereich.add(status, BorderLayout.CENTER);
        fortschritt.setIndeterminate(true);
        fortschritt.setVisible(false);
        bereich.add(fortschritt, BorderLayout.EAST);
        return bereich;
    }

    private void ordnerWaehlen(JTextField feld) {
        JFileChooser auswahl = new JFileChooser(feld.getText().isBlank() ? null : feld.getText());
        auswahl.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (auswahl.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            feld.setText(auswahl.getSelectedFile().getAbsolutePath());
        }
    }

    private void vorschauErstellen() {
        if (quelle.getText().isBlank() || ziel.getText().isBlank()) {
            fehler("Bitte Quelle und Ziel wählen.");
            return;
        }
        Path q = Path.of(quelle.getText().trim());
        Path z = Path.of(ziel.getText().trim());
        EINSTELLUNGEN.put("quelle", q.toString());
        EINSTELLUNGEN.put("ziel", z.toString());
        imHintergrund("Lese alle Titel in " + q + " ein …", () -> Vorschlaege.einlesen(q, z), vorschlaege -> {
            try {
                tabelle.anzeigen(vorschlaege, q);
            } catch (IOException e) {
                fehlerAnzeigen("Titel einlesen", e);
                return;
            }
            nachAenderung();
        });
    }

    private void nachAenderung() {
        List<Aktion> plan = tabelle.plan();
        einsortieren.setEnabled(anzahl(plan, Art.EINSORTIEREN) + quarantaene(plan) > 0);
        status.setText(zusammenfassung(plan));
    }

    private void einsortieren() {
        List<Aktion> plan = tabelle.plan();
        Path z = tabelle.ziel();
        long anzahl = anzahl(plan, Art.EINSORTIEREN);
        long quarantaene = quarantaene(plan);
        int antwort = JOptionPane.showConfirmDialog(this,
                anzahl + " Filme nach\n" + z + "\nverschieben?\n\n"
                        + quarantaene + " Doubletten (identisch oder schlechter) kommen nach "
                        + z.resolve(Planer.QUARANTAENE) + ".\n"
                        + "Alles andere (übrige Doubletten, nicht erkannte, ausgelassene Filme, Konflikte) bleibt liegen.\n"
                        + "Der Lauf lässt sich danach rückgängig machen.",
                "Einsortieren", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (antwort != JOptionPane.OK_OPTION) {
            return;
        }
        einsortieren.setEnabled(false);
        imHintergrund("Verschiebe " + anzahl + " Filme …", () -> {
            long fehlt = Ausfuehrung.fehlenderPlatz(plan, z);
            if (fehlt > 0) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "Auf dem Ziellaufwerk fehlen %.1f GB. Es wurde nichts verschoben.", fehlt / 1e9));
            }
            return Ausfuehrung.ausfuehren(plan, z, stilleAusgabe());
        }, erledigt -> {
            JOptionPane.showMessageDialog(this, erledigt + " Filme einsortiert, " + quarantaene
                    + " Doubletten in " + Planer.QUARANTAENE + " verschoben.", "Fertig",
                    JOptionPane.INFORMATION_MESSAGE);
            vorschauErstellen();
        });
    }

    private void rueckgaengigMachen() {
        if (ziel.getText().isBlank()) {
            fehler("Bitte das Ziel wählen, dessen letzter Lauf rückgängig gemacht werden soll.");
            return;
        }
        Path z = Path.of(ziel.getText().trim());
        int antwort = JOptionPane.showConfirmDialog(this,
                "Den letzten Lauf in\n" + z + "\nrückgängig machen?\nDie Filme kommen an ihren alten Platz zurück.",
                "Rückgängig", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (antwort != JOptionPane.OK_OPTION) {
            return;
        }
        ByteArrayOutputStream meldungen = new ByteArrayOutputStream();
        imHintergrund("Mache den letzten Lauf rückgängig …",
                () -> Ausfuehrung.rueckgaengig(z, new PrintStream(meldungen, true, StandardCharsets.UTF_8)),
                zurueck -> {
                    String text = zurueck < 0 ? "Es gibt keinen Lauf, der rückgängig gemacht werden kann."
                            : zurueck + " Dateien an ihren alten Platz zurückverschoben.";
                    String hinweise = meldungen.toString(StandardCharsets.UTF_8).trim();
                    JOptionPane.showMessageDialog(this, hinweise.isEmpty() ? text : text + "\n\n" + hinweise,
                            "Rückgängig", JOptionPane.INFORMATION_MESSAGE);
                    tabelle.leeren();
                    einsortieren.setEnabled(false);
                    status.setText(text + " Für neue Vorschläge „Titel einlesen“ klicken.");
                });
    }

    private <T> void imHintergrund(String meldung, Callable<T> arbeit, Consumer<T> danach) {
        vorschau.setEnabled(false);
        rueckgaengig.setEnabled(false);
        fortschritt.setVisible(true);
        status.setText(meldung);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return arbeit.call();
            }

            @Override
            protected void done() {
                fortschritt.setVisible(false);
                vorschau.setEnabled(true);
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

    static String zusammenfassung(List<Aktion> plan) {
        if (plan.isEmpty()) {
            return "Keine Filme gefunden.";
        }
        return anzahl(plan, Art.EINSORTIEREN) + " werden einsortiert, "
                + anzahl(plan, Art.DOUBLETTE) + " Doubletten (davon " + quarantaene(plan) + " in Quarantäne), "
                + anzahl(plan, Art.UNERKANNT) + " nicht erkannt, "
                + anzahl(plan, Art.KONFLIKT) + " Konflikte, " + anzahl(plan, Art.AUSGELASSEN) + " ausgelassen. "
                + "Titel und Jahr lassen sich per Doppelklick ändern. Es wurde noch nichts verändert.";
    }

    private static long anzahl(List<Aktion> plan, Art art) {
        return plan.stream().filter(a -> a.art() == art).count();
    }

    private static long quarantaene(List<Aktion> plan) {
        return plan.stream().filter(Aktion::inQuarantaene).count();
    }

    /** Protokolliert den Fehler mit Stacktrace und zeigt ihn mit Hinweis auf das Logfile an. */
    private void fehlerAnzeigen(String beiAktion, Throwable fehler) {
        LOG.log(Level.SEVERE, "Fehler bei: " + beiAktion, fehler);
        status.setText("Fehler: " + Log.beschreibung(fehler));
        fehler(Log.beschreibung(fehler) + "\n\nDetails stehen im Logfile:\n" + Log.datei());
    }

    private void fehler(String text) {
        JOptionPane.showMessageDialog(this, text, "Filmorganizer", JOptionPane.WARNING_MESSAGE);
    }

    private static PrintStream stilleAusgabe() {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    /** Färbt die Statusspalte, damit Doubletten und Probleme sofort auffallen. */
    private static final class StatusDarstellung extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object wert, boolean ausgewaehlt,
                boolean fokus, int zeile, int spalte) {
            Component c = super.getTableCellRendererComponent(table, wert, ausgewaehlt, fokus, zeile, spalte);
            if (!ausgewaehlt) {
                c.setForeground(switch (String.valueOf(wert)) {
                    case "Wird einsortiert" -> new Color(0x1B7F3B);
                    case "Doublette", PlanTabelle.IN_QUARANTAENE -> new Color(0xB35C00);
                    case "Ausgelassen" -> Color.GRAY;
                    default -> new Color(0xB00020);
                });
            }
            return c;
        }
    }
}
