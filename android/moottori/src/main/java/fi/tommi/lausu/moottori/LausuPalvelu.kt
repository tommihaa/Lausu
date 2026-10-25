// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Tommi Haanranta
package fi.tommi.lausu.moottori

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import fi.tommi.lausu.kerros.Avaaja
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

const val TAG = "Lausu"
const val GOOGLE = "com.google.android.tts"

fun lataaAvaaja(context: Context): Avaaja = Avaaja(
    context.assets.open("lukusanat.json").use { it.readBytes().toString(Charsets.UTF_8) },
    context.assets.open("sijat.dawg").use { it.readBytes() },
)

/**
 * Puhemoottori, joka avaa suomen tekstin Lausun kerroksella ja välittää sen Googlen moottorille.
 *
 * Googlen ääni haetaan synthesizeToFile-kutsulla tavalliseen tiedostoon, ja tiedostoa luetaan
 * perässä sen kasvaessa. Tapa valittiin kokeessa 25.10.2026: viive oli Pixel 8a:lla noin 7 ms
 * suurempi kuin Googlella suoraan, kun putki lisäsi 80–140 ms (docs/ANDROID.md › Kokeen tulos).
 */
class LausuPalvelu : TextToSpeechService() {

    private data class Muoto(val taajuus: Int, val koodaus: Int, val kanavat: Int, val kehys: Int)

    private class Lausuma(val id: String) {
        val valmis = CountDownLatch(1)
        @Volatile var virhe: Int? = null
        @Volatile var pysaytetty = false
    }

    private lateinit var google: TextToSpeech
    private val muotoValmis = CountDownLatch(1)
    @Volatile private var muoto: Muoto? = null
    private val avaajaValmis = CountDownLatch(1)
    @Volatile private var avaaja: Avaaja? = null
    private val kaynnissa = ConcurrentHashMap<String, Lausuma>()
    @Volatile private var nykyinen: Lausuma? = null
    private val laskuri = AtomicInteger()

    // Synteesisäikeen välimuisti, ettei joka lausumalla tehdä turhia binder-kutsuja.
    private var googlenKieli = ""
    private var googlenNopeus = -1
    private var googlenKorkeus = -1

    @Volatile private var kieli = arrayOf("fin", "FIN", "")

    override fun onCreate() {
        thread(name = "avaaja") {
            try {
                val t0 = SystemClock.elapsedRealtime()
                avaaja = lataaAvaaja(this)
                Log.i(TAG, "Kerros ladattu ${SystemClock.elapsedRealtime() - t0} ms:ssa")
            } catch (e: Exception) {
                // Ilman kerrosta teksti kulkee ennallaan: väärä avaus olisi pahempi kuin ei avausta.
                Log.e(TAG, "Kerroksen lataus epäonnistui", e)
            }
            avaajaValmis.countDown()
        }
        // Googlen asiakas ennen super.onCreate():a, koska se kutsuu onLoadLanguage():a.
        google = TextToSpeech(this, { tila ->
            Log.i(TAG, "Google-asiakas alustettu, tila $tila")
            if (tila == TextToSpeech.SUCCESS) thread(name = "kalibrointi") { kalibroi() }
        }, GOOGLE)
        google.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { kaynnissa[id]?.valmis?.countDown() }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String) { kaynnissa[id]?.valmis?.countDown() }
            override fun onError(id: String, koodi: Int) {
                kaynnissa[id]?.let { it.virhe = koodi; it.valmis.countDown() }
            }
            override fun onStop(id: String, kesken: Boolean) { kaynnissa[id]?.valmis?.countDown() }
        })
        super.onCreate()
    }

    override fun onDestroy() {
        google.shutdown()
        super.onDestroy()
    }

    /** Äänen muoto luetaan kerran oikean tiedoston otsakkeesta ennen ensimmäistä lausumaa. */
    private fun kalibroi() {
        google.language = Locale("fi", "FI")
        googlenKieli = "fin-FIN"
        val tiedosto = File(cacheDir, "kalibrointi.wav")
        val l = Lausuma("kalibrointi")
        kaynnissa[l.id] = l
        google.synthesizeToFile("koe", Bundle(), tiedosto, l.id)
        val ehti = l.valmis.await(15, TimeUnit.SECONDS)
        kaynnissa.remove(l.id)
        if (!ehti || l.virhe != null || tiedosto.length() < 44) {
            Log.e(TAG, "Kalibrointi epäonnistui: ehti=$ehti virhe=${l.virhe} koko=${tiedosto.length()}")
            return
        }
        val b = ByteBuffer.wrap(tiedosto.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        tiedosto.delete()
        val kanavat = b.getShort(22).toInt()
        val taajuus = b.getInt(24)
        val bitit = b.getShort(34).toInt()
        val koodaus = when (bitit) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            32 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        muoto = Muoto(taajuus, koodaus, kanavat, kanavat * bitit / 8)
        Log.i(TAG, "Kalibrointi: $taajuus Hz, $bitit bittiä, $kanavat kanava(a)")
        muotoValmis.countDown()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        when (lang) {
            "fin" -> if (country == "FIN") TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
            "eng" -> if (country == "USA") TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val tulos = onIsLanguageAvailable(lang, country, variant)
        if (tulos >= TextToSpeech.LANG_AVAILABLE) kieli = arrayOf(lang ?: "", country ?: "", "")
        return tulos
    }

    override fun onGetLanguage(): Array<String> = kieli

    override fun onStop() {
        val l = nykyinen ?: return
        l.pysaytetty = true
        google.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        if (!muotoValmis.await(15, TimeUnit.SECONDS)) {
            Log.e(TAG, "Äänen muoto puuttuu, lausuma hylätään")
            callback.error()
            return
        }
        val m = muoto!!
        asetaGoogle(request)
        val alkuperainen = request.charSequenceText.toString()
        val teksti = if (request.language == "fin") avaa(alkuperainen) else alkuperainen
        val l = Lausuma("u${laskuri.incrementAndGet()}")
        kaynnissa[l.id] = l
        nykyinen = l

        val syotto = Syotto(callback, m.kehys)
        try {
            if (callback.start(m.taajuus, m.koodaus, m.kanavat) != TextToSpeech.SUCCESS) return
            seuraa(teksti, l, syotto)
            if (!l.pysaytetty) callback.done()
        } catch (e: IOException) {
            if (!l.pysaytetty) {
                Log.e(TAG, "Välitys katkesi", e)
                callback.error()
            }
        } finally {
            kaynnissa.remove(l.id)
            if (nykyinen === l) nykyinen = null
            // Lausumien teksti vain kehitysversion lokiin: ruudunlukijan teksti on käyttäjän dataa.
            if (BuildConfig.DEBUG) {
                val eka = syotto.ensimmainen?.let { "%.1f".format(Locale.ROOT, (it - t0) / 1e6) } ?: "-"
                Log.d(TAG, "${l.id} ensimmäinen ääni $eka ms «$alkuperainen» -> «$teksti»")
            }
        }
    }

    private fun avaa(teksti: String): String {
        if (!avaajaValmis.await(5, TimeUnit.SECONDS)) return teksti
        val a = avaaja ?: return teksti
        return try {
            a.avaa(teksti)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Avaus epäonnistui, teksti ennallaan", e)
            teksti
        }
    }

    private fun asetaGoogle(request: SynthesisRequest) {
        val k = "${request.language}-${request.country}"
        if (k != googlenKieli) {
            google.language = Locale(request.language, request.country)
            googlenKieli = k
        }
        if (request.speechRate != googlenNopeus) {
            google.setSpeechRate(request.speechRate / 100f)
            googlenNopeus = request.speechRate
        }
        if (request.pitch != googlenKorkeus) {
            google.setPitch(request.pitch / 100f)
            googlenKorkeus = request.pitch
        }
    }

    /**
     * Google kirjoittaa tiedostoon, ja sitä luetaan perässä. Kasvavasta tiedostosta luku
     * palauttaa -1 ennen kuin Google on valmis, joten silloin odotetaan eikä lueta -1:tä loppuna.
     */
    private fun seuraa(teksti: String, l: Lausuma, syotto: Syotto) {
        val tiedosto = File(cacheDir, "${l.id}.wav")
        val tulos = google.synthesizeToFile(teksti, Bundle(), tiedosto, l.id)
        if (tulos != TextToSpeech.SUCCESS) throw IOException("synthesizeToFile palautti $tulos")
        try {
            FileInputStream(tiedosto).use { sisaan ->
                val puskuri = ByteArray(8192)
                while (!l.pysaytetty) {
                    val valmisJo = l.valmis.count == 0L
                    val n = sisaan.read(puskuri)
                    when {
                        n > 0 -> syotto.syota(puskuri, n)
                        valmisJo -> return
                        else -> Thread.sleep(2)
                    }
                }
            }
        } finally {
            tiedosto.delete()
        }
    }

    /** Ohittaa WAV-otsakkeen ja antaa äänen eteenpäin kokonaisina näytekehyksinä. */
    private class Syotto(val callback: SynthesisCallback, val kehys: Int) {
        private var ohitettava = 44
        private val kesken = ByteArray(kehys)
        private var keskenPituus = 0
        private val ulos = ByteArray(callback.maxBufferSize)
        var ensimmainen: Long? = null

        fun syota(data: ByteArray, pituus: Int) {
            var i = minOf(ohitettava, pituus)
            ohitettava -= i
            while (i < pituus) {
                var o = 0
                if (keskenPituus > 0) {
                    System.arraycopy(kesken, 0, ulos, 0, keskenPituus)
                    o = keskenPituus
                    keskenPituus = 0
                }
                val maara = minOf(pituus - i, ulos.size - o)
                System.arraycopy(data, i, ulos, o, maara)
                i += maara
                o += maara
                val kokonaiset = o - o % kehys
                if (kokonaiset > 0) {
                    if (ensimmainen == null) ensimmainen = SystemClock.elapsedRealtimeNanos()
                    callback.audioAvailable(ulos, 0, kokonaiset)
                }
                keskenPituus = o - kokonaiset
                System.arraycopy(ulos, kokonaiset, kesken, 0, keskenPituus)
            }
        }
    }
}
