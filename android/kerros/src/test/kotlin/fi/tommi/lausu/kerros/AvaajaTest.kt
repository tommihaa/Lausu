package fi.tommi.lausu.kerros

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Ajaa samat tapaukset kuin tests/test_avaa.py (tests/tapaukset.txt). Polut tulevat
 * kerros/build.gradle.kts:stä, joten data ja tapaukset luetaan repon juuresta eikä kopioista.
 */
class AvaajaTest {

    @Test
    fun tapaukset() {
        val avaaja = Avaaja.lataa(File(System.getProperty("lausu.data")))
        val tapaukset = Tapaukset.lue(File(System.getProperty("lausu.tapaukset")).readLines(Charsets.UTF_8))
        val virheet = Tapaukset.aja(avaaja, tapaukset)
        virheet.forEach(::println)
        println("${tapaukset.size - virheet.size}/${tapaukset.size} läpi")
        assertEquals(0, virheet.size, "${virheet.size} tapausta epäonnistui")
    }
}
