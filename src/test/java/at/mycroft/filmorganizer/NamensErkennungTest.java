package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NamensErkennungTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Cats.and.Dogs.3.Pfoten.vereint.2020.German.DL.AC3.Dubbed.1080p.BluRay.x264-PsO | Cats and Dogs 3 Pfoten vereint | 2020 | 1080
            Cats.Ein.schnurriges.Abenteuer.2018.German.AC3D.DL.1080p.BluRay.x264-CLASSiCALHD | Cats Ein schnurriges Abenteuer | 2018 | 1080
            Das.Dschungelbuch.2016.German.DTSHD.1080p.BluRay.x264-FDHQ                       | Das Dschungelbuch              | 2016 | 1080
            (2011) Der gestiefelte Kater (1080p)                                             | Der gestiefelte Kater          | 2011 | 1080
            Der Pate (1972)                                                                  | Der Pate                       | 1972 | 0
            Blade.Runner.2049.2017.German.DL.2160p.UHD.BluRay.x265-GRP                       | Blade Runner 2049              | 2017 | 2160
            1917.2019.German.DL.720p.WEBRip.x264-GRP                                         | 1917                           | 2019 | 720
            Matrix 1999 German DL x264-GRUPPE2020                                            | Matrix                         | 1999 | 0
            Le_Fabuleux_Destin_d_Amelie_Poulain_2001_FRENCH_1080p                            | Le Fabuleux Destin d Amelie Poulain | 2001 | 1080
            """)
    void erkenntTitelJahrUndAufloesung(String name, String titel, int jahr, int aufloesung) {
        assertEquals(new FilmInfo(titel, jahr, aufloesung), NamensErkennung.erkennen(name).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"Der gestiefelte Kater (1080p)", "lhd-kater-1080p", "WodkaE-The.Jungle.Book.1080p", "Daten-Filmorganizer"})
    void ohneJahrKeinErgebnis(String name) {
        assertTrue(NamensErkennung.erkennen(name).isEmpty());
    }
}
