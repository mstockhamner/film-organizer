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
        } else if (mitSchalter(args, "vorschau")) {
            vorschauAusgeben(planErstellen(Path.of(args[1]), Path.of(args[2]), liegenLassen(args)), aus);
        } else if (mitSchalter(args, "ausfuehren")) {
            ausfuehren(Path.of(args[1]), Path.of(args[2]), liegenLassen(args), aus);
        } else if (args.length == 2 && args[0].equals("rueckgaengig")) {
            int anzahl = Ausfuehrung.rueckgaengig(Path.of(args[1]), aus);
            aus.println(anzahl < 0 ? "Nichts rückgängig zu machen." : anzahl + " Dateien zurückverschoben.");
        } else if (abgleich(args, "abgleichen")) {
            abgleichAusgeben(Abgleich.abgleichen(Path.of(args[1]), Path.of(args[2]), inSich(args)), aus);
        } else if (abgleich(args, "abgleichen-ausfuehren")) {
            abgleichAusfuehren(Path.of(args[1]), Path.of(args[2]), inSich(args), aus);
        } else if (args.length == 3 && args[0].equals("abgleich-rueckgaengig")) {
            int anzahl = Abgleich.rueckgaengig(Path.of(args[1]), Path.of(args[2]), aus);
            aus.println(anzahl < 0 ? "Kein Abgleich rückgängig zu machen." : anzahl + " Dateien zurückverschoben.");
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

                  --doubletten=quarantaene       Doubletten nach den Vorschlägen behandeln (Standard):
                                                 identische und schlechtere in die Quarantäne _Doubletten,
                                                 bessere ersetzen die vorhandene Fassung
                  --doubletten=liegen            alle Doubletten in der Quelle liegen lassen

                  abgleichen <A> <B>             gleicht zwei organisierte Sammlungen auf Doubletten ab,
                                                 ändert nichts (A ist die Hauptsammlung)
                  abgleichen-ausfuehren <A> <B>  zeigt die Funde und verschiebt nach Bestätigung die nicht
                                                 behaltenen Fassungen in die Quarantäne ihrer Sammlung
                  --auch-in-sich                 prüft zusätzlich jede Sammlung in sich
                  abgleich-rueckgaengig <A> <B>  macht den letzten Abgleich rückgängig
                """;
    }

    private static void abgleichAusfuehren(Path a, Path b, boolean inSich, PrintStream aus) throws IOException {
        List<Abgleich.Fund> funde = Abgleich.abgleichen(a, b, inSich);
        abgleichAusgeben(funde, aus);
        long anzahl = funde.stream().filter(f -> f.vorschlag() == Abgleich.Wahl.ERSTE_BEHALTEN
                || f.vorschlag() == Abgleich.Wahl.ZWEITE_BEHALTEN).count();
        if (anzahl == 0) {
            aus.println("Nichts zu tun.");
            return;
        }
        aus.print(anzahl + " Fassungen nach den Vorschlägen in die Quarantäne ihrer Sammlung verschieben? "
                + "Zum Bestätigen \"ja\" eingeben: ");
        aus.flush();
        String antwort = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (antwort == null || !antwort.trim().equalsIgnoreCase("ja")) {
            aus.println("Abgebrochen, nichts verändert.");
            return;
        }
        int erledigt = Abgleich.ausfuehren(funde, Map.of(), aus);
        aus.println(erledigt + " Fassungen in die Quarantäne verschoben. Rückgängig mit: abgleich-rueckgaengig \""
                + a + "\" \"" + b + "\"");
    }

    private static boolean abgleich(String[] args, String befehl) {
        return args[0].equals(befehl) && (args.length == 3 || args.length == 4 && args[3].equals("--auch-in-sich"));
    }

    private static boolean inSich(String[] args) {
        return args.length == 4 && args[3].equals("--auch-in-sich");
    }

    /** Gibt die Funde eines Abgleichs nach Art gruppiert mit Vorschlag aus. */
    static void abgleichAusgeben(List<Abgleich.Fund> funde, PrintStream aus) throws IOException {
        if (funde.isEmpty()) {
            aus.println("Keine Doubletten gefunden.");
            return;
        }
        for (Abgleich.Art art : Abgleich.Art.values()) {
            List<Abgleich.Fund> mitArt = funde.stream().filter(f -> f.art() == art).toList();
            if (mitArt.isEmpty()) {
                continue;
            }
            aus.println();
            aus.println(art.text() + " (" + mitArt.size() + ")");
            for (Abgleich.Fund f : mitArt) {
                aus.println("  " + f.erste().info().titel()
                        + (f.erste().info().jahr() > 0 ? " (" + f.erste().info().jahr() + ")" : ""));
                aus.println("      1 " + f.erste().anzeige());
                aus.println("      2 " + f.zweite().anzeige());
                aus.println("      " + f.hinweis());
                aus.println("      Vorschlag: " + f.vorschlag().text());
                for (Aktion.Verschiebung v : Abgleich.verschiebungen(f, f.vorschlag())) {
                    aus.println("      " + v.von().getFileName() + " -> " + v.nach());
                }
            }
        }
        aus.println();
    }

    private static boolean mitSchalter(String[] args, String befehl) {
        return args[0].equals(befehl) && (args.length == 3
                || args.length == 4 && (args[3].equals("--doubletten=liegen") || args[3].equals("--doubletten=quarantaene")));
    }

    private static boolean liegenLassen(String[] args) {
        return args.length == 4 && args[3].equals("--doubletten=liegen");
    }

    private static List<Aktion> planErstellen(Path quelle, Path ziel, boolean liegenLassen) throws IOException {
        List<Aktion> plan = planErstellen(quelle, ziel);
        return liegenLassen ? planErstellen(quelle, ziel, allesLiegenLassen(plan, Map.of())) : plan;
    }

    /** Ergänzt die Entscheidungen so, dass keine Doublette verschoben oder ersetzt wird. */
    static Map<Path, Entscheidung> allesLiegenLassen(List<Aktion> plan, Map<Path, Entscheidung> entscheidungen) {
        Map<Path, Entscheidung> ergebnis = new HashMap<>(entscheidungen);
        for (Aktion aktion : plan) {
            if (aktion.art() != Art.FASSUNG && aktion.auswahl().contains(Entscheidung.LIEGEN_LASSEN)) {
                ergebnis.put(aktion.film().video(), Entscheidung.LIEGEN_LASSEN);
            }
        }
        return ergebnis;
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
                if (art != Art.EINSORTIEREN) {
                    aus.println("      " + aktion.hinweis());
                }
                if (!aktion.auswahl().isEmpty() && art != Art.FASSUNG) {
                    aus.println("      Entscheidung: " + aktion.entscheidung().text());
                }
                for (Aktion.Verschiebung v : aktion.verschiebungen()) {
                    aus.println("      " + v.von().getFileName() + " -> " + v.nach());
                }
                for (Aktion.NeueDatei neu : aktion.neueDateien()) {
                    aus.println("      neu angelegt: " + neu.pfad());
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
            case ERSETZEN -> "Bessere Fassungen, ersetzen die vorhandene (die alte kommt nach " + Planer.QUARANTAENE + ")";
            case UMSORTIEREN -> "Filme aus der Sammlung, die in den Ordner ihrer Filmreihe kommen";
            case JAHR_ERGAENZEN -> "Filme der Sammlung ohne Jahr, deren Jahr jetzt bekannt ist (Ordner und Dateien bekommen es)";
            case FASSUNG -> "Andere Fassungen eines vorhandenen Films, Rückfrage beim Ausführen";
            case UNERKANNT -> "Nicht erkannt, bleiben liegen";
            case KONFLIKT -> "Konflikte, bleiben liegen";
            case AUSGELASSEN -> "Ausgelassen, bleiben liegen";
        };
    }

    private static void ausfuehren(Path quelle, Path ziel, boolean liegenLassen, PrintStream aus) throws IOException {
        BufferedReader ein = new BufferedReader(new InputStreamReader(System.in));
        List<Aktion> plan = planErstellen(quelle, ziel, liegenLassen);
        vorschauAusgeben(plan, aus);
        Map<Path, Entscheidung> entscheidungen = fassungenNachfragen(plan, ein, aus);
        if (!entscheidungen.isEmpty()) {
            if (liegenLassen) {
                entscheidungen = allesLiegenLassen(plan, entscheidungen);
            }
            plan = planErstellen(quelle, ziel, entscheidungen);
        }
        long anzahl = plan.stream().filter(a -> a.art() == Art.EINSORTIEREN).count();
        long ersetzt = plan.stream().filter(a -> a.art() == Art.ERSETZEN).count();
        long umsortiert = plan.stream().filter(a -> a.art() == Art.UMSORTIEREN || a.art() == Art.JAHR_ERGAENZEN).count();
        long quarantaene = plan.stream().filter(Aktion::inQuarantaene).count();
        if (anzahl == 0 && ersetzt == 0 && umsortiert == 0 && quarantaene == 0) {
            aus.println("Nichts einzusortieren.");
            return;
        }
        long fehlt = Ausfuehrung.fehlenderPlatz(plan, ziel);
        if (fehlt > 0) {
            aus.printf(Locale.ROOT, "Auf dem Ziellaufwerk fehlen %.1f GB. Abbruch.%n", fehlt / 1e9);
            return;
        }
        aus.print(anzahl + " Filme einsortieren, " + ersetzt + " vorhandene durch bessere Fassungen ersetzen "
                + "(die alten kommen nach " + Planer.QUARANTAENE + ") und " + quarantaene + " Doubletten nach "
                + Planer.QUARANTAENE + " verschieben, " + umsortiert + " Filme der Sammlung in ihre Filmreihe "
                + "umsortieren? Zum Bestätigen \"ja\" eingeben: ");
        aus.flush();
        String antwort = ein.readLine();
        if (antwort == null || !antwort.trim().equalsIgnoreCase("ja")) {
            aus.println("Abgebrochen, nichts verändert.");
            return;
        }
        int erledigt = Ausfuehrung.ausfuehren(plan, quelle, ziel, aus);
        aus.println(erledigt + " Filme einsortiert oder ersetzt, " + quarantaene + " Doubletten in " + Planer.QUARANTAENE
                + ". Rückgängig mit: rueckgaengig \"" + ziel + "\"");
    }

    /**
     * Fragt für jede andere Fassung eines vorhandenen Films nach, ob beide behalten werden, die neue in
     * die Quarantäne kommt oder liegen bleibt. Ohne Antwort b oder q bleibt sie liegen.
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
            } else {
                entscheidungen.put(aktion.film().video(), Entscheidung.LIEGEN_LASSEN);
            }
        }
        return entscheidungen;
    }
}