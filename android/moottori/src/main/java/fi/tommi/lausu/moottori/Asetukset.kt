// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Tommi Haanranta
package fi.tommi.lausu.moottori

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * Lausun asetukset. Avautuu puhemoottorin rataskuvakkeesta (Asetukset › Tekstistä puheeksi).
 * Käyttäjä valitsee, mikä moottori lukee Lausun avaaman tekstin.
 */
class Asetukset : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Lausu"
        val dp = { n: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt() }
        val juuri = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        juuri.addView(TextView(this).apply {
            text = "Lukeva moottori"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        juuri.addView(TextView(this).apply {
            text = "Lausu kirjoittaa luvut ja lyhenteet auki ja antaa tekstin tälle moottorille luettavaksi. Ääni on valitun moottorin ääni."
            setPadding(0, dp(8), 0, dp(16))
        })
        val ryhma = RadioGroup(this)
        val asennetut = Kohde.asennetut(this)
        val nyt = Kohde.lue(this)
        for ((paketti, nimi) in Kohde.TUETUT.filter { it.first in asennetut }) {
            ryhma.addView(RadioButton(this).apply {
                id = tunniste(paketti)
                text = nimi
                setPadding(dp(8), dp(12), 0, dp(12))
            })
        }
        ryhma.check(tunniste(nyt))
        ryhma.setOnCheckedChangeListener { _, valittu ->
            Kohde.TUETUT.firstOrNull { tunniste(it.first) == valittu }?.let { Kohde.aseta(this, it.first) }
        }
        juuri.addView(ryhma)
        setContentView(juuri)
    }

    // Kiinteä tunniste paketin järjestyksestä, jotta valinta säilyy näkymän uudelleenluonnissa.
    private fun tunniste(paketti: String) = 1000 + Kohde.TUETUT.indexOfFirst { it.first == paketti }
}
