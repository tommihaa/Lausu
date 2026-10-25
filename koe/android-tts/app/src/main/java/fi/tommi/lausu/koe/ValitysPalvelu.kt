package fi.tommi.lausu.koe

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

const val TAG = "LausuKoe"
const val GOOGLE = "com.google.android.tts"

/**
 * Puhemoottori, joka avaa tekstin ja välittää sen Googlen moottorille.
 *
 * Googlen ääni haetaan synthesizeToFile-kutsulla, koska julkinen asiakasrajapinta ei anna
 * ääntä muuten ulos. Kolme tapaa (asetus "tila"):
 * - putki: kirjoitus putkeen, luetaan sitä mukaa kuin ääntä tulee
 * - seuraa: kirjoitus tavalliseen tiedostoon, luetaan perässä sen kasvaessa
 * - tiedosto: odotetaan koko lause valmiiksi ja luetaan vasta sitten (vertailukohta)
 */
class ValitysPalvelu : TextToSpeechService() {

    private data class Muoto(val taajuus: Int, val koodaus: Int, val kanavat: Int, val kehys: Int)

    private class Lausuma(val id: String) {
        val valmis = CountDownLatch(1)
        @Volatile var virhe: Int? = null
        @Volatile var pysaytetty = false
        @Volatile var pysaytysHetki = 0L
        @Volatile var lukupaa: ParcelFileDescriptor? = null
    }

    private lateinit var google: TextToSpeech
    private val muotoValmis = CountDownLatch(1)
    @Volatile private var muoto: Muoto? = null
    private val kaynnissa = ConcurrentHashMap<String, Lausuma>()
    @Volatile private var nykyinen: Lausuma? = null
    private val laskuri = AtomicInteger()

    // Synteesisäikeen välimuisti, ettei joka lausumalla tehdä turhia binder-kutsuja.
    private var googlenKieli = ""
    private var googlenNopeus = -1
    private var googlenKorkeus = -1

    @Volatile private var kieli = arrayOf("fin", "FIN", "")

    override fun onCreate() {
        // Googlen asiakas ennen super.onCreate():a, koska se kutsuu onLoadLanguage():a.
        google = TextToSpeech(this, { tila ->
            Log.i(TAG, "Google-asiakas alustettu, tila $tila")
            if (tila == TextToSpeech.SUCCESS) thread(name = "kalibrointi") { kalibroi(); kerroAanet() }
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

    /** Äänen muoto luetaan kerran oikean tiedoston otsakkeesta, koska putkessa otsake on tyhjä. */
    private fun kalibroi() {
        google.language = Locale("fi", "FI")
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
        val kanavat = b.getShort(22).toInt()
        val taajuus = b.getInt(24)
        val bitit = b.getShort(34).toInt()
        val koodaus = when (bitit) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            32 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        muoto = Muoto(taajuus, koodaus, kanavat, kanavat * bitit / 8)
        Log.i(TAG, "Kalibrointi: $taajuus Hz, $bitit bittiä, $kanavat kanava(a), ${tiedosto.length()} tavua")
        muotoValmis.countDown()
    }

    /** Koekysymys 4: näkyvätkö Googlen äänet tälle moottorille valittaviksi. */
    private fun kerroAanet() {
        val aanet = google.voices ?: emptySet()
        val suomi = aanet.filter { it.locale.language == "fi" }
        Log.i(TAG, "Googlen ääniä ${aanet.size}, suomeksi ${suomi.size}")
        suomi.sortedBy { it.name }.forEach {
            Log.i(TAG, "  ${it.name} verkko=${it.isNetworkConnectionRequired} laatu=${it.quality}")
        }
        Log.i(TAG, "Googlen nykyinen ääni: ${google.voice?.name}")
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
        l.pysaytysHetki = SystemClock.elapsedRealtimeNanos()
        l.pysaytetty = true
        google.stop()
        try { l.lukupaa?.close() } catch (_: IOException) {}
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
        val teksti = avaa(request.charSequenceText.toString())
        val tila = getSharedPreferences("koe", Context.MODE_PRIVATE).getString("tila", "seuraa")!!
        val l = Lausuma("u${laskuri.incrementAndGet()}")
        kaynnissa[l.id] = l
        nykyinen = l

        val syotto = Syotto(callback, m.kehys, t0)
        try {
            if (callback.start(m.taajuus, m.koodaus, m.kanavat) != TextToSpeech.SUCCESS) return
            when (tila) {
                "putki" -> putki(teksti, l, syotto)
                "tiedosto" -> tiedosto(teksti, l, syotto)
                else -> seuraa(teksti, l, syotto)
            }
            if (!l.pysaytetty) callback.done()
        } catch (e: IOException) {
            if (!l.pysaytetty) {
                Log.e(TAG, "Välitys katkesi", e)
                callback.error()
            }
        } finally {
            kaynnissa.remove(l.id)
            if (nykyinen === l) nykyinen = null
            val loppu = SystemClock.elapsedRealtimeNanos()
            val pysaytys = if (l.pysaytetty) " pysäytyksestä poistumiseen ${ms(loppu - l.pysaytysHetki)} ms" else ""
            Log.i(TAG, "lausuma ${l.id} tila=$tila ensimmäinen ääni ${syotto.ensimmainen?.let { ms(it - t0) } ?: "-"} ms," +
                " ääntä ${syotto.tavut} tavua, kesto ${ms(loppu - t0)} ms, googlen virhe ${l.virhe}$pysaytys")
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

    private fun putki(teksti: String, l: Lausuma, syotto: Syotto) {
        val (luku, kirjoitus) = ParcelFileDescriptor.createPipe()
        l.lukupaa = luku
        val tulos = google.synthesizeToFile(teksti, Bundle(), kirjoitus, l.id)
        kirjoitus.close() // Google sai oman kopionsa, putki sulkeutuu kun se sulkee omansa
        if (tulos != TextToSpeech.SUCCESS) throw IOException("synthesizeToFile palautti $tulos")
        ParcelFileDescriptor.AutoCloseInputStream(luku).use { lue(it, l, syotto, odotaLoppua = false) }
    }

    private fun seuraa(teksti: String, l: Lausuma, syotto: Syotto) {
        val tiedosto = File(cacheDir, "${l.id}.wav")
        val tulos = google.synthesizeToFile(teksti, Bundle(), tiedosto, l.id)
        if (tulos != TextToSpeech.SUCCESS) throw IOException("synthesizeToFile palautti $tulos")
        try {
            FileInputStream(tiedosto).use { lue(it, l, syotto, odotaLoppua = true) }
        } finally {
            tiedosto.delete()
        }
    }

    private fun tiedosto(teksti: String, l: Lausuma, syotto: Syotto) {
        val tiedosto = File(cacheDir, "${l.id}.wav")
        val tulos = google.synthesizeToFile(teksti, Bundle(), tiedosto, l.id)
        if (tulos != TextToSpeech.SUCCESS) throw IOException("synthesizeToFile palautti $tulos")
        while (!l.valmis.await(5, TimeUnit.MILLISECONDS)) if (l.pysaytetty) return
        try {
            FileInputStream(tiedosto).use { lue(it, l, syotto, odotaLoppua = false) }
        } finally {
            tiedosto.delete()
        }
    }

    /**
     * Lukee äänen ja syöttää sen eteenpäin. Kasvavasta tiedostosta luku palauttaa -1 ennen
     * kuin Google on valmis, joten silloin odotetaan valmista eikä lueta -1:tä loppuna.
     */
    private fun lue(sisaan: InputStream, l: Lausuma, syotto: Syotto, odotaLoppua: Boolean) {
        val puskuri = ByteArray(8192)
        while (!l.pysaytetty) {
            val valmisJo = l.valmis.count == 0L
            val n = sisaan.read(puskuri)
            when {
                n > 0 -> syotto.syota(puskuri, n)
                !odotaLoppua || valmisJo -> return
                else -> Thread.sleep(2)
            }
        }
    }

    /** Ohittaa WAV-otsakkeen ja antaa äänen eteenpäin kokonaisina näytekehyksinä. */
    private class Syotto(val callback: SynthesisCallback, val kehys: Int, val t0: Long) {
        private var ohitettava = 44
        private val kesken = ByteArray(kehys)
        private var keskenPituus = 0
        private val ulos = ByteArray(callback.maxBufferSize)
        var tavut = 0L
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
                    tavut += kokonaiset
                }
                keskenPituus = o - kokonaiset
                System.arraycopy(ulos, kokonaiset, kesken, 0, keskenPituus)
            }
        }
    }

    /** Lausun kerroksen paikka. Kokeessa teksti kulkee läpi sellaisenaan. */
    private fun avaa(teksti: String): String = teksti
}

fun ms(ns: Long): String = "%.1f".format(Locale.ROOT, ns / 1_000_000.0)
