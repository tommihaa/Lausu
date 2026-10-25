// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Tommi Haanranta
package fi.tommi.lausu.moottori

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech

const val SAMSUNG = "com.samsung.SMT"

/**
 * Moottori, jolle Lausu antaa avatun tekstin luettavaksi. Tuettuja ovat moottorit, joiden
 * kautta Lausu on kuunneltu (docs/ANDROID.md › Kuuntelukoe). Oletus on Google.
 */
object Kohde {
    val TUETUT = listOf(GOOGLE to "Google", SAMSUNG to "Samsung")
    const val AVAIN = "kohde"

    fun asetukset(context: Context): SharedPreferences =
        context.getSharedPreferences("lausu", Context.MODE_PRIVATE)

    /** Tallennettu kohde, jos se on yhä asennettu. Muuten Google. */
    fun lue(context: Context): String {
        val tallennettu = asetukset(context).getString(AVAIN, GOOGLE) ?: GOOGLE
        return if (tallennettu in asennetut(context)) tallennettu else GOOGLE
    }

    fun aseta(context: Context, paketti: String) {
        asetukset(context).edit().putString(AVAIN, paketti).apply()
    }

    /** Tuetut moottorit, jotka ovat laitteessa puhemoottorina. */
    fun asennetut(context: Context): List<String> {
        val palvelut = context.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName }
            .toSet()
        return TUETUT.map { it.first }.filter { it in palvelut }
    }
}
