// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Tommi Haanranta
package fi.tommi.lausu.moottori

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import fi.tommi.lausu.kerros.Tapaukset
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Kehitystarkistus laitteella adb:llä. Tulokset logcatiin (tagi Lausu).
 *
 * Tapaukset Androidin regex-moottorilla (tiedosto ensin sovelluksen files-kansioon):
 *   am start -n fi.tommi.lausu/.moottori.Tarkistus --es tapaukset tapaukset.txt
 * Yksi teksti kerroksen läpi, ja --ez puhu true puhuu sen myös Lausun moottorilla hiljaa:
 *   am start -n fi.tommi.lausu/.moottori.Tarkistus --es teksti "Nouse 2. kerrokseen." --ez puhu true
 * Lauseet WAV-tiedostoiksi valitulla moottorilla kuuntelukoetta varten (rivit "tunnus<TAB>teksti",
 * tulos files/<kansio>/<tunnus>.wav):
 *   am start -n fi.tommi.lausu/.moottori.Tarkistus --es lauseet lauseet.txt
 *       --es moottori com.samsung.SMT --es kansio samsung
 * Lausun lukeva moottori (sama kuin Asetukset-näkymän valinta), esim. ennen äänitystä:
 *   am start -n fi.tommi.lausu/.moottori.Tarkistus --es kohde com.samsung.SMT
 */
class Tarkistus : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tiedosto = intent.getStringExtra("tapaukset")
        val teksti = intent.getStringExtra("teksti")
        val puhu = intent.getBooleanExtra("puhu", false)
        val lauseet = intent.getStringExtra("lauseet")
        val moottori = intent.getStringExtra("moottori") ?: packageName
        val kansio = intent.getStringExtra("kansio") ?: "aanet"
        intent.getStringExtra("kohde")?.let {
            Kohde.aseta(this, it)
            Log.i(TAG, "TARKISTUS kohde: ${Kohde.lue(this)}")
        }
        thread(name = "tarkistus") {
            if (lauseet != null) {
                aanita(File(filesDir, lauseet), moottori, File(filesDir, kansio))
                runOnUiThread { finish() }
                return@thread
            }
            val avaaja = lataaAvaaja(this)
            if (tiedosto != null) {
                val tapaukset = Tapaukset.lue(File(filesDir, tiedosto).readLines(Charsets.UTF_8))
                val virheet = Tapaukset.aja(avaaja, tapaukset)
                virheet.take(40).forEach { Log.w(TAG, it) }
                Log.i(TAG, "TARKISTUS $tiedosto: ${tapaukset.size - virheet.size}/${tapaukset.size} läpi")
            }
            if (teksti != null) {
                Log.i(TAG, "TARKISTUS «$teksti» -> «${avaaja.avaa(teksti)}»")
                if (puhu) puhu(teksti)
            }
            runOnUiThread { finish() }
        }
    }

    /** Kirjoittaa jokaisen lauseen tiedostoksi annetulla moottorilla suomeksi, oletusäänellä. */
    private fun aanita(lahde: File, moottori: String, kohde: File) {
        kohde.mkdirs()
        val rivit = lahde.readLines(Charsets.UTF_8).filter { '\t' in it }
        val alustettu = java.util.concurrent.CountDownLatch(1)
        var tila = TextToSpeech.ERROR
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(applicationContext, { tila = it; alustettu.countDown() }, moottori)
        alustettu.await(20, java.util.concurrent.TimeUnit.SECONDS)
        if (tila != TextToSpeech.SUCCESS) {
            Log.e(TAG, "ÄÄNITYS $moottori: alustus epäonnistui ($tila)")
            return
        }
        val kieli = tts.setLanguage(Locale("fi", "FI"))
        Log.i(TAG, "ÄÄNITYS $moottori: kieli $kieli, ääni ${tts.voice?.name}")
        var valmis = java.util.concurrent.CountDownLatch(1)
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) = valmis.countDown()
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String) = valmis.countDown()
        })
        for (rivi in rivit) {
            val (tunnus, lause) = rivi.split('\t', limit = 2)
            valmis = java.util.concurrent.CountDownLatch(1)
            val wav = File(kohde, "$tunnus.wav")
            tts.synthesizeToFile(lause, Bundle(), wav, tunnus)
            val ehti = valmis.await(30, java.util.concurrent.TimeUnit.SECONDS)
            Log.i(TAG, "ÄÄNITYS $moottori $tunnus: ehti=$ehti ${wav.length()} tavua")
        }
        tts.shutdown()
    }

    /** Puhuu tekstin Lausun omalla moottorilla äänenvoimakkuudella 0 ja odottaa lopun. */
    private fun puhu(teksti: String) {
        val valmis = java.util.concurrent.CountDownLatch(1)
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(applicationContext, { tila ->
            if (tila != TextToSpeech.SUCCESS) { valmis.countDown(); return@TextToSpeech }
            tts.language = Locale("fi", "FI")
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) = valmis.countDown()
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(id: String) = valmis.countDown()
            })
            val hiljaa = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f) }
            tts.speak(teksti, TextToSpeech.QUEUE_FLUSH, hiljaa, "tarkistus")
        }, packageName)
        valmis.await(20, java.util.concurrent.TimeUnit.SECONDS)
        tts.shutdown()
    }
}
