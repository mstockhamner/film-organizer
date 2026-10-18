package at.mycroft.filmorganizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Prüfsummen für den Vergleich von Videodateien. Läuft vollständig lokal.
 */
public final class Pruefsumme {

    private static final int BLOCK = 1024 * 1024;

    private Pruefsumme() {
    }

    /**
     * Schnelle Prüfsumme über Größe sowie Anfang, Mitte und Ende der Datei (je 1 MiB).
     * Reicht, um gleiche Dateien zu erkennen, ohne mehrere Gigabyte zu lesen.
     */
    public static String schnell(Path datei) throws IOException {
        MessageDigest digest = sha256();
        try (FileChannel kanal = FileChannel.open(datei, StandardOpenOption.READ)) {
            long groesse = kanal.size();
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(groesse).array());
            for (long position : new long[] {0, Math.max(0, groesse / 2 - BLOCK / 2), Math.max(0, groesse - BLOCK)}) {
                ByteBuffer puffer = ByteBuffer.allocate(BLOCK);
                kanal.read(puffer, position);
                puffer.flip();
                digest.update(puffer);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Prüfsumme über die ganze Datei, zum Prüfen einer Kopie. */
    public static String voll(Path datei) throws IOException {
        MessageDigest digest = sha256();
        byte[] puffer = new byte[BLOCK];
        try (InputStream ein = Files.newInputStream(datei)) {
            int gelesen;
            while ((gelesen = ein.read(puffer)) > 0) {
                digest.update(puffer, 0, gelesen);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt in dieser Java-Laufzeit", e);
        }
    }
}
