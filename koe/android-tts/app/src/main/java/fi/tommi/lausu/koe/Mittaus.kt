package fi.tommi.lausu.koe

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Mittaa saman lausuman suoraan Googlen moottorilla ja Lausun kautta. Äänenvoimakkuus on 0,
 * joten puhelin ei puhu ääneen. Käynnistys adb:llä, tulokset logcatiin (tagi LausuKoe):
 *
 *   am start -n fi.tommi.lausu.koe/.Mittaus --es moottori lausu --es tila putki --es koe viive
 *
 * Viive: aika speak()-kutsusta ensimmäiseen äänipalaan (onAudioAvailable).
 * Keskeytys: stop() heti ensimmäisen palan jälkeen; aika onStop-kutsuun ja palat sen jälkeen.
 */
class Mittaus : Activity() {

    private class Kierros {
        val alku = SystemClock.elapsedRealtimeNanos()
        @Volatile var ensimmainen = 0L
        @Volatile var pysaytetty = 0L
        @Volatile var loppu = 0L
        val palojaPysaytyksenJalkeen = AtomicInteger()
        val ensimmainenPala = CountDownLatch(1)
        val valmis = CountDownLatch(1)
    }

    @Volatile private var kierros: Kierros? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val moottori = intent.getStringExtra("moottori") ?: "lausu"
        val tila = intent.getStringExtra("tila") ?: "seuraa"
        val koe = intent.getStringExtra("koe") ?: "viive"
        val toistot = intent.getIntExtra("toistot", 8)
        val teksti = intent.getStringExtra("teksti")
            ?: if (koe == "viive") "Heitto 1 kolmesta, painike" else PITKA

        getSharedPreferences("koe", Context.MODE_PRIVATE).edit().putString("tila", tila).commit()
        val paketti = if (moottori == "google") GOOGLE else packageName

        lateinit var tts: TextToSpeech
        tts = TextToSpeech(applicationContext, { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "mittaus: moottori $paketti ei alustunut ($status)")
                finish()
                return@TextToSpeech
            }
            tts.language = Locale("fi", "FI")
            thread(name = "mittaus") {
                aja(tts, moottori, tila, koe, toistot, teksti)
                tts.shutdown()
                runOnUiThread { finish() }
            }
        }, paketti)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onAudioAvailable(id: String, audio: ByteArray) {
                val k = kierros ?: return
                if (k.ensimmainen == 0L) {
                    k.ensimmainen = SystemClock.elapsedRealtimeNanos()
                    k.ensimmainenPala.countDown()
                }
                if (k.pysaytetty != 0L) k.palojaPysaytyksenJalkeen.incrementAndGet()
            }
            override fun onDone(id: String) = lopeta()
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String) = lopeta()
            override fun onStop(id: String, kesken: Boolean) = lopeta()
            private fun lopeta() {
                val k = kierros ?: return
                k.loppu = SystemClock.elapsedRealtimeNanos()
                k.valmis.countDown()
            }
        })
    }

    private fun aja(tts: TextToSpeech, moottori: String, tila: String, koe: String, toistot: Int, teksti: String) {
        val nimi = if (moottori == "google") "google" else "lausu/$tila"
        val tulokset = mutableListOf<Double>()
        val jalkeen = mutableListOf<Int>()
        val hiljaa = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f) }
        for (i in 0 until toistot) {
            val k = Kierros()
            kierros = k
            tts.speak(teksti, TextToSpeech.QUEUE_FLUSH, hiljaa, "m$i")
            if (koe == "keskeytys") {
                if (!k.ensimmainenPala.await(15, TimeUnit.SECONDS)) { Log.e(TAG, "$nimi: ei ääntä"); continue }
                k.pysaytetty = SystemClock.elapsedRealtimeNanos()
                tts.stop()
                k.valmis.await(5, TimeUnit.SECONDS)
                Thread.sleep(500) // myöhästyneet palat näkyvät tässä ajassa
                val ms = (k.loppu - k.pysaytetty) / 1e6
                Log.i(TAG, "$nimi keskeytys #$i: stop → onStop ${"%.1f".format(Locale.ROOT, ms)} ms, palat stopin jälkeen ${k.palojaPysaytyksenJalkeen.get()}")
                if (i > 0) { tulokset += ms; jalkeen += k.palojaPysaytyksenJalkeen.get() }
            } else {
                if (!k.valmis.await(20, TimeUnit.SECONDS) || k.ensimmainen == 0L) { Log.e(TAG, "$nimi: kierros $i jäi kesken"); continue }
                val ms = (k.ensimmainen - k.alku) / 1e6
                Log.i(TAG, "$nimi viive #$i: ensimmäinen ääni ${"%.1f".format(Locale.ROOT, ms)} ms, koko ${"%.1f".format(Locale.ROOT, (k.loppu - k.alku) / 1e6)} ms")
                if (i > 0) tulokset += ms
            }
            Thread.sleep(300)
        }
        tulokset.sort()
        val mediaani = if (tulokset.isEmpty()) Double.NaN else tulokset[tulokset.size / 2]
        Log.i(TAG, "YHTEENVETO $nimi $koe: mediaani ${"%.1f".format(Locale.ROOT, mediaani)} ms," +
            " min ${tulokset.firstOrNull()?.let { "%.1f".format(Locale.ROOT, it) }}," +
            " max ${tulokset.lastOrNull()?.let { "%.1f".format(Locale.ROOT, it) }}, n=${tulokset.size}" +
            if (koe == "keskeytys") ", palat stopin jälkeen yhteensä ${jalkeen.sum()}" else " (ensimmäinen kierros lämmittelynä pois)")
    }

    companion object {
        const val PITKA = "Tämä on pitkä lause, jonka tarkoitus on kestää useita sekunteja, jotta " +
            "puhe ehditään katkaista kesken. Ruudunlukija katkaisee puheen aina, kun käyttäjä " +
            "siirtyy seuraavaan kohtaan, joten katkaisun on oltava välitön."
    }
}
