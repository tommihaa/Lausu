package fi.tommi.lausu.kerros

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Vertaa Kotlin-kerrosta Python-kerroksen tuloksiin (tests/vertailukorpus.py). Ajetaan vain,
 * kun korpus annetaan: gradlew :kerros:test -Plausu.vertailu=<polku>.
 */
class VertailuTest {

    @Test
    fun samaKuinPython() {
        val polku = System.getProperty("lausu.vertailu").orEmpty()
        assumeTrue(polku.isNotEmpty(), "vertailukorpusta ei annettu")
        val avaaja = Avaaja.lataa(File(System.getProperty("lausu.data")))
        val tapaukset = Tapaukset.lue(File(polku).readLines(Charsets.UTF_8))
        val erot = Tapaukset.aja(avaaja, tapaukset)
        erot.take(40).forEach(::println)
        println("vertailu: ${tapaukset.size - erot.size}/${tapaukset.size} samoja")
        assertEquals(0, erot.size, "${erot.size} eroa Python-toteutukseen")
    }
}
