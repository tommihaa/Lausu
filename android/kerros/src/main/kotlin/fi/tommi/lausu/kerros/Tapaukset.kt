// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Tommi Haanranta
package fi.tommi.lausu.kerros

/**
 * Testitapausten lukija: tests/tapaukset.txt ja tests/vertailukorpus.py:n tuloste.
 * Käytössä JVM-testeissä ja moottorin laitetarkistuksessa, jotta muoto luetaan yhdellä tavalla.
 */
object Tapaukset {
    data class Tapaus(val tunnus: String, val syote: String, val odotettu: String)

    /** Rivi on "syöte⇥odotettu" tai "@tunnus⇥syöte⇥odotettu", valinnainen "# ..."-kenttä lopussa. */
    fun lue(rivit: List<String>): List<Tapaus> = rivit
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { rivi ->
            val kentat = rivi.split("\t").filter { !it.startsWith("#") }.toMutableList()
            val tunnus = if (kentat[0].startsWith("@")) kentat.removeAt(0).substring(1) else ""
            require(kentat.size == 2) { "virheellinen rivi: $rivi" }
            Tapaus(tunnus, kentat[0], kentat[1])
        }

    /** Ajaa tapaukset ja palauttaa virheilmoitukset. */
    fun aja(avaaja: Avaaja, tapaukset: List<Tapaus>): List<String> = tapaukset.mapNotNull { t ->
        val saatiin = avaaja.avaa(t.syote)
        if (saatiin == t.odotettu) null
        else "VIRHE ${t.tunnus} '${t.syote}'\n  odotettu: '${t.odotettu}'\n  saatiin:  '$saatiin'"
    }
}
