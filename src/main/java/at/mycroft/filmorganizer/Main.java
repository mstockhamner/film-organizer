package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.ui.Fenster;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Einstiegspunkt des Filmorganizers. Arbeitet ausschließlich lokal, ohne Netzwerkzugriff.
 * Ohne Argumente startet die Oberfläche, mit Argumenten die Kommandozeile:
 *
 * <pre>
 * vorschau     &lt;quelle&gt; &lt;ziel&gt;   zeigt, was passieren würde, ändert nichts
 * ausfuehren   &lt;quelle&gt; &lt;ziel&gt;   zeigt die Vorschau und verschiebt nach Bestätigung
 * rueckgaengig &lt;ziel&gt;            macht den letzten Lauf rückgängig
 * </pre>
 */
public final class Main {

    private static final Logger LOG = Log.fuer(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Log.einrichten();
        LOG.info(() -> "Aufruf: " + (args.length == 0 ? "Oberfläche" : String.join(" ", args)));
        PrintStream aus = System.out;
        if (args.length == 0) {
            Fenster.starten();
        } else if (args.length == 3 && args[0].equals("vorschau")) {
            vorschauAusgeben(planErstellen(Path.of(args[1]), Path.of(args[2])), aus);
        } else if (args.length == 3 && args[0].equals("ausfuehren")) {
            ausfuehren(Path.of(args[1]), Path.of(args[2]), aus);
        } else if (args.length == 2 && args[0].equals("rueckgaengig")) {
            int anzahl = Ausfuehrung.rueckgaengig(Path.of(args[1]), aus);
            aus.println(anzahl < 0 ? "Nichts rückgängig zu machen." : anzahl + " Dateien zurückverschoben.");
        } else {
            aus.println(hilfe());
        }
    }

    static String hilfe() {
        return """
                Filmorganizer

                  (ohne Argumente)               startet die Oberfläche
                  vorschau     <quelle> <ziel>   zeigt, was passieren würde, ändert nichts
                  ausfuehren   <quelle> <ziel>   zeigt die Vorschau und verschiebt nach Bestätigung
                  rueckgaengig <ziel>            macht den letzten Lauf rückgängig
                """;
    }

    /** Durchsucht die Quelle und erstellt den Plan, ändert nichts. */
    public static List<Aktion> planErstellen(Path quelle, Path ziel) throws IOException {
        if (!Files.isDirectory(quelle)) {
            throw new IOException("Quellordner nicht gefunden: " + quelle);
        }
        List<Film> filme = Scanner.scannen(quelle, ziel);
        return Planer.planen(filme, Katalog.laden(ziel), ziel);
    }

    static void vorschauAusgeben(List<Aktion> plan, PrintStream aus) {
        for (Art art : Art.values()) {
            List<Aktion> aktionen = plan.stream().filter(a -> a.art() == art).toList();
            if (aktionen.isEmpty()) {
                continue;
            }
            aus.println();
            aus.println(ueberschrift(art) + " (" + aktionen.size() + ")");
            for (Aktion aktion : aktionen) {
                aus.println("  " + aktion.film().video());
                if (art == Art.EINSORTIEREN) {
                    for (Aktion.Verschiebung v : aktion.verschiebungen()) {
                        aus.println("      -> " + v.nach());
                    }
                } else {
                    aus.println("      " + aktion.hinweis());
                }
            }
        }
        aus.println();
    }

    private static String ueberschrift(Art art) {
        return switch (art) {
            case EINSORTIEREN -> "Wird einsortiert";
            case DOUBLETTE -> "Doubletten, bleiben liegen";
            case UNERKANNT -> "Nicht erkannt, bleiben liegen";
            case KONFLIKT -> "Konflikte, bleiben liegen";
            case AUSGELASSEN -> "Ausgelassen, bleiben liegen";
        };
    }

    private static void ausfuehren(Path quelle, Path ziel, PrintStream aus) throws IOException {
        List<Aktion> plan = planErstellen(quelle, ziel);
        vorschauAusgeben(plan, aus);
        long anzahl = plan.stream().filter(a -> a.art() == Art.EINSORTIEREN).count();
        if (anzahl == 0) {
            aus.println("Nichts einzusortieren.");
            return;
        }
        long fehlt = Ausfuehrung.fehlenderPlatz(plan, ziel);
        if (fehlt > 0) {
            aus.printf(Locale.ROOT, "Auf dem Ziellaufwerk fehlen %.1f GB. Abbruch.%n", fehlt / 1e9);
            return;
        }
        aus.print(anzahl + " Filme verschieben? Zum Bestätigen \"ja\" eingeben: ");
        aus.flush();
        String antwort = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (antwort == null || !antwort.trim().equalsIgnoreCase("ja")) {
            aus.println("Abgebrochen, nichts verändert.");
            return;
        }
        int erledigt = Ausfuehrung.ausfuehren(plan, ziel, aus);
        aus.println(erledigt + " Filme einsortiert. Rückgängig mit: rueckgaengig \"" + ziel + "\"");
    }
}
