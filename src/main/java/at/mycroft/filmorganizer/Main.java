package at.mycroft.filmorganizer;

/**
 * Einstiegspunkt des Filmorganizers.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println(begruessung());
    }

    /** Der Begrüßungstext, als eigene Methode, damit er ohne Konsole prüfbar ist. */
    static String begruessung() {
        return "Filmorganizer";
    }
}
