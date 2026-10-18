package at.mycroft.filmorganizer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Protokolliert in die Konsole und in ein Logfile im Benutzerordner
 * ({@code ~/.filmorganizer/filmorganizer-0.log}, rotierend über -0 bis -2, je höchstens 5 MB). Das Logfile bleibt
 * auf diesem Rechner, es wird nichts verschickt.
 */
public final class Log {

    private static final String FORMAT = "%1$tF %1$tT %4$-7s [%3$s] %5$s%6$s%n";
    private static final Logger LOG = Logger.getLogger(Log.class.getName());
    private static final Logger EIGENE = Logger.getLogger("at.mycroft.filmorganizer");
    private static volatile Consumer<Throwable> beiUnerwartetemFehler = fehler -> { };
    private static volatile String datei;

    private Log() {
    }

    /** Richtet Konsole, Logfile und die Behandlung unerwarteter Fehler ein. Mehrfacher Aufruf schadet nicht. */
    public static synchronized void einrichten() {
        if (datei != null) {
            return;
        }
        System.setProperty("java.util.logging.SimpleFormatter.format", FORMAT);
        LogManager.getLogManager().reset();
        Logger wurzel = Logger.getLogger("");
        wurzel.setLevel(Level.INFO);
        // Eigene Meldungen ausführlicher; Java selbst (Swing, AWT) bleibt bei INFO, sonst wird das Logfile geflutet.
        EIGENE.setLevel(Level.FINE);
        Formatter format = new SimpleFormatter();

        ConsoleHandler konsole = new ConsoleHandler();
        konsole.setLevel(Level.INFO);
        konsole.setFormatter(format);
        wurzel.addHandler(konsole);

        Path ordner = Path.of(System.getProperty("user.home"), ".filmorganizer");
        Path ziel = ordner.resolve("filmorganizer-0.log");
        try {
            Files.createDirectories(ordner);
            // %g ist die Generation der Rotation: -0 ist immer das aktuelle Logfile.
            String muster = ordner.toString().replace("%", "%%") + File.separator + "filmorganizer-%g.log";
            Handler logfile = new FileHandler(muster, 5_000_000, 3, true);
            logfile.setEncoding("UTF-8");
            logfile.setLevel(Level.FINE);
            logfile.setFormatter(format);
            wurzel.addHandler(logfile);
            datei = ziel.toString();
        } catch (IOException e) {
            datei = "(kein Logfile: " + Log.beschreibung(e) + ")";
            LOG.log(Level.WARNING, "Logfile kann nicht angelegt werden: " + ziel, e);
        }

        Thread.setDefaultUncaughtExceptionHandler((thread, fehler) -> {
            LOG.log(Level.SEVERE, "Unerwarteter Fehler im Thread " + thread.getName(), fehler);
            beiUnerwartetemFehler.accept(fehler);
        });
        LOG.info(() -> "Filmorganizer gestartet, Java " + System.getProperty("java.version")
                + ", Logfile: " + datei);
    }

    /** Pfad des Logfiles, für Hinweise in Fehlermeldungen. */
    public static String datei() {
        return datei;
    }

    /** Wird zusätzlich zum Protokollieren bei jedem unerwarteten Fehler aufgerufen, z.B. für einen Dialog. */
    public static void beiUnerwartetemFehler(Consumer<Throwable> aktion) {
        beiUnerwartetemFehler = aktion;
    }

    /** Kurze, lesbare Beschreibung eines Fehlers, auch wenn er keine Meldung hat (z.B. NullPointerException). */
    public static String beschreibung(Throwable fehler) {
        String meldung = fehler.getMessage();
        String art = fehler.getClass().getSimpleName();
        return meldung == null || meldung.isBlank() ? art : art + ": " + meldung;
    }

    public static Logger fuer(Class<?> klasse) {
        return Logger.getLogger(klasse.getName());
    }
}
