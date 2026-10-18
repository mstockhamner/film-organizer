package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import at.mycroft.filmorganizer.ui.Fenster;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        return planErstellen(quelle, ziel, Map.of());
    }

    /** Wie {@link #planErstellen(Path, Path)}, mit Entscheidungen zu anderen Fassungen. */
    public static List<Aktion> planErstellen(Path quelle, Path ziel, Map<Path, Entscheidung> entscheidungen)
            throws IOException {
        if (!Files.isDirectory(quelle)) {
            throw new IOException("Quellordner nicht gefunden: " + quelle);
        }
        List<Film> filme = Scanner.scannen(quelle, ziel);
        return Planer.planen(filme, Katalog.laden(ziel), ziel, entscheidungen);
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
                    if (aktion.inQuarantaene()) {
                        aus.println("      -> Quarantäne: " + aktion.verschiebungen().get(0).nach());
                    }
                }
            }
        }
        aus.println();
    }

    private static String ueberschrift(Art art) {
        return switch (art) {
            case EINSORTIEREN -> "Wird einsortiert";
            case DOUBLETTE -> "Doubletten (identische und schlechtere kommen nach " + Planer.QUARANTAENE
                    + ", andere bleiben liegen)";
            case FASSUNG -> "Andere Fassungen eines vorhandenen Films, Rückfrage beim Ausführen";
            case UNERKANNT -> "Nicht erkannt, bleiben liegen";
            case KONFLIKT -> "Konflikte, bleiben liegen";
            case AUSGELASSEN -> "Ausgelassen, bleiben liegen";
        };
    }

    private static void ausfuehren(Path quelle, Path ziel, PrintStream aus) throws IOException {
        BufferedReader ein = new BufferedReader(new InputStreamReader(System.in));
        List<Aktion> plan = planErstellen(quelle, ziel);
        vorschauAusgeben(plan, aus);
        Map<Path, Entscheidung> entscheidungen = fassungenNachfragen(plan, ein, aus);
        if (!entscheidungen.isEmpty()) {
            plan = planErstellen(quelle, ziel, entscheidungen);
        }
        long anzahl = plan.stream().filter(a -> a.art() == Art.EINSORTIEREN).count();
        long quarantaene = plan.stream().filter(Aktion::inQuarantaene).count();
        if (anzahl == 0 && quarantaene == 0) {
            aus.println("Nichts einzusortieren.");
            return;
        }
        long fehlt = Ausfuehrung.fehlenderPlatz(plan, ziel);
        if (fehlt > 0) {
            aus.printf(Locale.ROOT, "Auf dem Ziellaufwerk fehlen %.1f GB. Abbruch.%n", fehlt / 1e9);
            return;
        }
        aus.print(anzahl + " Filme einsortieren und " + quarantaene + " Doubletten nach " + Planer.QUARANTAENE
                + " verschieben? Zum Bestätigen \"ja\" eingeben: ");
        aus.flush();
        String antwort = ein.readLine();
        if (antwort == null || !antwort.trim().equalsIgnoreCase("ja")) {
            aus.println("Abgebrochen, nichts verändert.");
            return;
        }
        int erledigt = Ausfuehrung.ausfuehren(plan, ziel, aus);
        aus.println(erledigt + " Filme einsortiert, " + quarantaene + " Doubletten in " + Planer.QUARANTAENE
                + ". Rückgängig mit: rueckgaengig \"" + ziel + "\"");
    }

    /**
     * Fragt für jede andere Fassung eines vorhandenen Films nach, ob beide behalten werden, die neue in
     * die Quarantäne kommt oder liegen bleibt. Liefert nur die getroffenen Entscheidungen.
     */
    static Map<Path, Entscheidung> fassungenNachfragen(List<Aktion> plan, BufferedReader ein, PrintStream aus)
            throws IOException {
        Map<Path, Entscheidung> entscheidungen = new HashMap<>();
        for (Aktion aktion : plan) {
            if (aktion.art() != Art.FASSUNG) {
                continue;
            }
            aus.println(aktion.film().video());
            aus.println("  " + aktion.hinweis());
            aus.print("  [b]eide behalten, [q]uarantäne oder [l]iegen lassen? ");
            aus.flush();
            String antwort = ein.readLine();
            String wahl = antwort == null ? "" : antwort.trim().toLowerCase(Locale.ROOT);
            if (wahl.startsWith("b")) {
                entscheidungen.put(aktion.film().video(), Entscheidung.BEIDE_BEHALTEN);
            } else if (wahl.startsWith("q")) {
                entscheidungen.put(aktion.film().video(), Entscheidung.QUARANTAENE);
            }
        }
        return entscheidungen;
    }
}