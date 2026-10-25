// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Tommi Haanranta
package fi.tommi.lausu.kerros

import java.io.File
import java.util.Locale
import java.util.regex.MatchResult
import java.util.regex.Pattern

/**
 * Lausu: suomen tekstin avauskerros ruudunlukua varten. Kotlin-vastine tiedostolle
 * addon/globalPlugins/lausu/avaa.py. Säännöt, järjestys ja nimet seuraavat Python-toteutusta,
 * jotta molemmat voidaan tarkistaa samoja tapauksia vasten (tests/tapaukset.txt).
 *
 * Säännölliset lausekkeet poikkeavat Pythonista yhdessä asiassa: Javan \w, \d, \s ja \b ovat
 * oletuksena ASCII-luokkia, kun Pythonissa ne ovat Unicode-luokkia. Siksi ne on kirjoitettu
 * auki Unicode-ominaisuuksina (W, D, S alla). Androidin regex-lippuihin ei luoteta, koska
 * UNICODE_CHARACTER_CLASS-lipun tuki vaihtelee.
 *
 * Periaatteet:
 * - Ajon aikana tehdään vain hakuja.
 * - Ei arvailua: jos sääntö ei tunnista tapausta varmasti, kohta jätetään ennalleen.
 */
class Avaaja(lukusanatJson: String, sijatDawg: ByteArray) {

    /** Korvaus ja sen perässä kulutettujen merkkien määrä (väli voi kuluttaa yksikön). */
    private class Korvaus(val teksti: String, val kulutettu: Int = 0)

    private class Konteksti(val laji: String, val luennat: Set<Pair<String, String>>? = null)

    private val cardAtoms: Map<Long, Map<String, String>>
    private val ordAtoms: Map<String, Map<String, String>>
    private val units: Map<String, String>
    private val words: Map<String, Map<String, String>>
    private val partitiveVerbs: Set<String>
    private val measureWords: Set<String>
    private val sijat = Sanapuu.lataa(sijatDawg)
    private val unitAlt: String
    private val passes: List<Pair<Pattern, (MatchResult, String) -> Korvaus?>>
    private val rangeUnit: Pattern

    init {
        @Suppress("UNCHECKED_CAST")
        val d = Json.lue(lukusanatJson) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun taulu(x: Any?) = x as Map<String, Map<String, String>>
        cardAtoms = taulu(d["card"]).mapKeys { it.key.toLong() }
        ordAtoms = taulu(d["ord"])
        @Suppress("UNCHECKED_CAST")
        units = d["units"] as Map<String, String>
        words = taulu(d["words"])
        @Suppress("UNCHECKED_CAST")
        partitiveVerbs = (d["partitiiviverbit"] as List<String>).toSet()
        @Suppress("UNCHECKED_CAST")
        measureWords = (d["mittasanat"] as List<String>).toSet()

        unitAlt = units.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val unitAltIlmanEuroa = units.keys.filter { it != "€" }.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val acr = (ACRONYM_WORD.keys + ACRONYM_SPELLED).sortedByDescending { it.length }.joinToString("|")
        val abbrAlt = ABBR.keys.sortedByDescending { it.length }.joinToString("|") {
            if (it.endsWith(".")) Regex.escape(it.dropLast(1)) + """\.""" else Regex.escape(it)
        }
        val months = MONTHS.joinToString("|")
        rangeUnit = re("""$SP?($unitAlt)(?![$W%°:])""")
        fun passi(p: Pattern, fn: (MatchResult, String) -> Korvaus?) = p to fn
        passes = listOf(
            passi(re("""(?<![$LETTER$D])($acr)(?::([$LOWER]+))?(?![$LETTER$D])"""), ::acronym),
            passi(re("""(?<![$W.])($abbrAlt)(?![$W.])""", ignoreCase = true), { m, t -> abbr(m, t)?.let(::Korvaus) }),
            passi(re("""(?<![$W+])(\+358$SP?|0)($D{1,3})((?:[ -]$D{2,7}){1,3})(?![$D,]|\.$D)"""), { m, _ -> phone(m)?.let(::Korvaus) }),
            passi(re("""(?<![$D.,])($D{1,2})\.($D{1,2})\.($D{4})?(?![$D:]|\.$D)"""), { m, t -> date(m, t)?.let(::Korvaus) }),
            // Windowsin pitkä päiväys (NVDA+F12 kahdesti): "25. lokakuuta 2026". Piste voi puuttua
            // ("perjantaina 10 heinäkuuta" Ilmatieteen laitoksen sivulla): kuukauden edellä luku on silti päiväys.
            passi(re("""$NB($D{1,2})\.?$SP+($months)(?:$SP+($D{4}))?(?![$W])""", ignoreCase = true), { m, _ -> dateNamed(m)?.let(::Korvaus) }),
            passi(re("""(?<![$W])(kello)$S+($D{1,2})(?:[.:]($D{2}))?(?:$S?[–—-]$S?($D{1,2})(?:[.:]($D{2}))?)?(?![$D:]|[.,]$D)""",
                ignoreCase = true), { m, _ -> time(m)?.let(::Korvaus) }),
            passi(re("""$NB($MINUS)?($INT)(?:,($D{2}|-))?$SP?€(?![$W:])"""), ::money),
            passi(re("""$NB($D+)(?:$SP?[–—]$SP?|-)($D+)(?![$D,:]|\.$D)"""), ::range),
            // Kauttaviiva kahden kokonaisluvun välissä: 2/5 -> kaksi kautta viisi (vaihe, arvosana,
            // osamäärä). Ketju (2024/10/05) ja kaksoispistepääte (3/4:n) jäävät ennalleen.
            passi(re("""$NB($D+)/($D+)(?![$W/:]|[.,]$D)"""), { m, _ -> slash(m)?.let(::Korvaus) }),
            passi(re("""$NB($MINUS)?($INT)(?:,($D+))?$SP?($unitAltIlmanEuroa)(?::([$LOWER]+))?(?![$W%°])"""), ::unit),
            passi(re("""$NB($INT)-([$LOWER]+)"""), { m, _ -> hyphen(m)?.let(::Korvaus) }),
            passi(re("""$NB($INT):([$LOWER]+)(?![$W])"""), { m, _ -> colon(m)?.let(::Korvaus) }),
            passi(re("""$NB($D+)\.(?=$SP+[$LOWER])"""), { m, t -> ordinalPass(m, t)?.let(::Korvaus) }),
            passi(re("""$NB($MINUS)?($D+),($D+)(?![$D,]|\.$D|/$D)"""), { m, t -> decimalPass(m, t)?.let(::Korvaus) }),
            // Kauttaviivan edellä luku jätetään, jos slash hylkäsi sen: puoliksi avattu "kolme/4" on pahempi.
            passi(re("""$NB($MINUS)?($INT)(?![$W]|[,.:]$D|:|/$D)"""), { m, t -> integer(m, t)?.let(::Korvaus) }),
        )
    }

    companion object {
        // Pythonin Unicode-luokat auki kirjoitettuina (ks. luokan kuvaus).
        private const val W = """\p{L}\p{N}_"""                     // \w merkkiluokan sisällä
        private const val D = """\p{Nd}"""                           // \d
        private const val S = """[\s\p{Z}\x1c-\x1f\x85]"""          // \s
        private const val SP = "[   ]"                     // välilyönti, sitova välilyönti, kapea sitova
        private const val GROUPED = """$D{1,3}(?:$SP$D{3})+(?!$D)""" // 1 347, 12 000 000
        private const val INT = """(?:$GROUPED|$D+)"""
        private const val MINUS = "[−-]"
        // Luvun alku: ei kiinni sanassa, toisessa luvussa eikä viivassa (3-2 on tulos).
        private const val NB = """(?<![$W,.:/–—−-])"""
        private const val LOWER = "a-zåäöšž"
        private const val LETTER = "A-Za-zÅÄÖåäöŠšŽžÉé"

        private val CASES = listOf("Nom", "Gen", "Par", "Ess", "Tra", "Ine", "Ela", "Ill",
            "Ade", "Abl", "All", "Abe", "Ins", "Com")
        private val NUMS = listOf("Sg", "Pl")

        private val DIGITS = listOf("nolla", "yksi", "kaksi", "kolme", "neljä", "viisi", "kuusi",
            "seitsemän", "kahdeksan", "yhdeksän")
        private val MONTHS = listOf("tammikuuta", "helmikuuta", "maaliskuuta", "huhtikuuta", "toukokuuta",
            "kesäkuuta", "heinäkuuta", "elokuuta", "syyskuuta", "lokakuuta",
            "marraskuuta", "joulukuuta")

        // Lyhenne -> auki kirjoitettu. Ehto: null = aina, "num" = vain luvun edellä,
        // "vuosi" = vain nelinumeroisen luvun edellä.
        private val ABBR = linkedMapOf(
            "esim." to ("esimerkiksi" to null), "ks." to ("katso" to null),
            "jne." to ("ja niin edelleen" to null), "mm." to ("muun muassa" to null),
            "ym." to ("ynnä muuta" to null), "yms." to ("ynnä muuta sellaista" to null),
            "tms." to ("tai muuta sellaista" to null), "ts." to ("toisin sanoen" to null),
            "vrt." to ("vertaa" to null), "huom." to ("huomaa" to null), "puh." to ("puhelin" to null),
            "klo" to ("kello" to null), "klo." to ("kello" to null),
            "n." to ("noin" to "num"), "s." to ("sivu" to "num"), "nro" to ("numero" to "num"),
            "v." to ("vuonna" to "vuosi"),
        )
        // Nämä voivat päättää virkkeen, jolloin piste on myös virkkeen loppu.
        private val SENTENCE_FINAL_ABBR = setOf("jne.", "ym.", "yms.", "tms.")
        private val NEGATION = setOf("en", "et", "ei", "emme", "ette", "eivät", "älä", "älkää")
        // Liitepartikkelit, jotka irrotetaan ennen verbihakua (rakastankin, odotatko).
        private val CLITIC = Regex("(?:kin|kaan|kään|ko|kö|han|hän|pa|pä)$")

        // Kirjainlyhenteet: sanana luettavat sanaksi, kirjaimittain luettavat kirjainten nimiksi.
        // Perustelut avaa.py:ssä.
        private val ACRONYM_WORD = mapOf("YLE" to "Yle", "KELA" to "Kela", "NATO" to "Nato", "HUS" to "Hus")
        private val LETTER_NAMES = mapOf('A' to "aa", 'B' to "bee", 'C' to "see", 'D' to "dee", 'E' to "ee",
            'F' to "äf", 'G' to "gee", 'H' to "hoo", 'I' to "ii", 'J' to "jii", 'K' to "koo", 'L' to "äl",
            'M' to "äm", 'N' to "än", 'O' to "oo", 'P' to "pee", 'Q' to "kuu", 'R' to "är", 'S' to "äs",
            'T' to "tee", 'U' to "uu", 'V' to "vee", 'W' to "kaksoisvee", 'X' to "äks", 'Y' to "yy",
            'Z' to "tseta", 'Ä' to "ää", 'Ö' to "öö")
        private val ACRONYM_SPELLED = setOf("EU", "USA", "YK", "ALV", "VR", "SAK", "EK", "THL", "HSL",
            "PDF", "USB", "TV", "VTT")
        // Konsonanttiin päättyvän kirjaimen nimen ä-loppuinen vartalo (THL:n -> ällän).
        private val LETTER_STEMS = mapOf("äf" to "äffä", "äl" to "ällä", "äm" to "ämmä", "än" to "ännä",
            "är" to "ärrä", "äs" to "ässä", "äks" to "äksä")

        // Kaksoispistepäätteen sijat yleisimmästä alkaen. Pääte hyväksytään vain, jos
        // generoitu muoto todella päättyy siihen (5:ään hylätään, koska viiteen ≠ -ään).
        private val SUFFIX_CASE = listOf(
            "(?:a|ä|ta|tä|tta|ttä)" to "Par", "n" to "Gen", "(?:na|nä)" to "Ess",
            "ksi" to "Tra", "(?:ssa|ssä)" to "Ine", "(?:sta|stä)" to "Ela",
            "(?:[aeiouyäö]{2}n|seen|hin)" to "Ill", "(?:lla|llä)" to "Ade",
            "(?:lta|ltä)" to "Abl", "lle" to "All",
        ).map { Regex(it.first) to it.second }

        private val SANA_ALUSSA = re("""$SP*([$LETTER]+)?""")
        private val SANA_ALUSSA_PAKKO = re("""$SP*([$LETTER]+)""")
        private val SANA_POIS_ALUSTA = Regex("""^$SP*[$LETTER]+""")
        private val VIRKKEEN_RAJA = Regex("[.!?;:,]")
        private val SANA = Regex("[$LETTER]+")
        private val LUKU_PERASSA = re("""$SP*$MINUS?$D""")
        private val VUOSI_PERASSA = re("""$SP*$D{4}(?!$D)""")
        private val ISO_SANA_PERASSA = re("""$S+[A-ZÅÄÖ]""")
        // Per-yksikkö: 3 €/kk, 50 km/h. Jakoviivan jälkeinen aikayksikkö luetaan inessiivissä
        // (kolme euroa kuukaudessa). Muut jakoviivat jäävät ennalleen (3 €/kpl).
        private val PER_UNITS = listOf("vrk", "min", "kk", "h", "s")
        private val PER = re("""/(${PER_UNITS.joinToString("|")})(?![$W%°/:])""")
        // Kuukauden nimi luvun perässä: luku on päiväys eikä lukumäärä.
        private val KUUKAUSI_PERASSA = re("""$SP+(?:${MONTHS.joinToString("|")})(?![$W])""", ignoreCase = true)

        private const val BILJOONA = 1_000_000_000_000L

        private fun re(p: String, ignoreCase: Boolean = false): Pattern =
            Pattern.compile(p, if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)

        /** Pythonin re.match: osuma vain merkkijonon alusta. */
        private fun Pattern.alusta(s: String): MatchResult? =
            matcher(s).let { if (it.lookingAt()) it.toMatchResult() else null }

        /** Pythonin str.isspace(): Kotlinin isWhitespace kattaa sitovat välilyönnit, ei NEL:iä. */
        private fun Char.pyTyhja() = isWhitespace() || this == '\u0085'
        private fun String.pyStrip() = trim { it.pyTyhja() }

        /** Kokonaisluku numeroista. Yli 15 numeroa kyllästyy, koska kaikki ≥ biljoona hylätään. */
        private fun luku(s: String): Long {
            val numerot = s.filter { it.isDigit() }
            return if (numerot.length > 15) BILJOONA * 1000 else numerot.toLong()
        }

        private fun numero(c: Char) = Character.digit(c, 10)

        /** Liittää osat, tai null jos jokin osa puuttuu (Pythonissa "None"-tarkistus). */
        private fun cat(vararg osat: String?): String? =
            if (osat.any { it == null }) null else osat.joinToString("")

        fun lataa(dataDir: File) = Avaaja(
            File(dataDir, "lukusanat.json").readText(Charsets.UTF_8),
            File(dataDir, "sijat.dawg").readBytes(),
        )
    }

    // ---------- lukusanojen kokoaminen taulukosta ----------

    /** Perusluku sanoina. Ryhmät erotetaan välilyönnillä, jotta syntetisaattori saa painolliset rajat. */
    fun card(n0: Long, case: String = "Sg+Nom"): String? {
        if (n0 == 0L) return words["nolla"]?.get(case)
        if (n0 >= BILJOONA) return null
        var n = n0
        val out = ArrayList<String?>()
        for (scale in longArrayOf(1_000_000_000L, 1_000_000L, 1000L)) {
            val q = n / scale
            n %= scale
            if (q > 0) {
                val atom = atom(scale, case)
                if (q == 1L) {
                    out += atom
                } else {
                    val group = below1000(q, case)
                    group[group.size - 1] = cat(group.last(), mult(scale, case))
                    out += group
                }
            }
        }
        if (n > 0) out += below1000(n, case)
        if (out.any { it == null }) return null
        return out.joinToString(" ")
    }

    private fun atom(n: Long, case: String): String? = cardAtoms.getValue(n)[case]

    private fun mult(scale: Long, case: String): String? {
        // kaksikymmentä, kaksisataa, kaksituhatta: kerrottava osa on perusmuodossa
        // partitiivissa, muissa sijoissa samassa sijassa (kahdellekymmenelle).
        val form = atom(scale, if (case == "Sg+Nom") "Sg+Par" else case)
        val sep = if (scale >= 1_000_000L) " " else ""
        return cat(sep, form)
    }

    private fun below1000(n0: Long, case: String): MutableList<String?> {
        val out = ArrayList<String?>()
        val h = n0 / 100
        val n = n0 % 100
        if (h > 0) out += if (h == 1L) atom(100, case) else cat(atom(h, case), mult(100, case))
        if (n > 0) out += below100(n, case)
        return out
    }

    private fun below100(n: Long, case: String): String? {
        if (n <= 10) return atom(n, case)
        if (n < 20) return cat(atom(n - 10, case), "toista")
        val t = n / 10
        val u = n % 10
        val s = cat(atom(t, case), mult(10, case))
        return if (u > 0) cat(s, atom(u, case)) else s
    }

    /** Järjestysluku sanoina, kaikki osat taipuvat (kahdennellekymmenennelleviidennelle). */
    fun ordinal(n: Long, form: String = "Sg+Nom"): String? {
        if (n !in 1L until 1_000_000L) return null
        val parts = ArrayList<String?>()
        val q = n / 1000
        val r = n % 1000
        if (q > 0) {
            parts += if (q == 1L) ordAtoms.getValue("1000")[form]
            else cat(ordBelow1000(q, form, final = false), ordAtoms.getValue("1000")[form])
        }
        if (r > 0) parts += ordBelow1000(r, form, final = true)
        if (parts.any { it == null }) return null
        return parts.joinToString(" ")
    }

    private fun ordUnit(u: Long, form: String, final: Boolean): String? {
        val avain = if (!final && u == 1L) "yhdes" else if (!final && u == 2L) "kahdes" else u.toString()
        return ordAtoms.getValue(avain)[form]
    }

    private fun ordBelow1000(n0: Long, form: String, final: Boolean): String? {
        var s: String? = ""
        val h = n0 / 100
        val n = n0 % 100
        if (h > 0) s = cat(s, if (h == 1L) "" else ordUnit(h, form, false), ordAtoms.getValue("100")[form])
        if (n > 0) {
            s = when {
                n <= 10 -> cat(s, if (n < 10) ordUnit(n, form, final) else ordAtoms.getValue("10")[form])
                n < 20 -> {
                    val u = n - 10
                    val avain = if (u == 1L) "yhdes" else if (u == 2L) "kahdes" else u.toString()
                    cat(s, ordAtoms.getValue(avain)[form], "toista")
                }
                else -> {
                    val t = n / 10
                    val u = n % 10
                    val k = cat(s, ordUnit(t, form, false), ordAtoms.getValue("10")[form])
                    if (u > 0) cat(k, ordUnit(u, form, final)) else k
                }
            }
        }
        return s
    }

    fun decimal(whole: Long, frac: String): String? {
        val w = card(whole) ?: return null
        val f = if (frac.length <= 2 && !frac.startsWith("0")) card(luku(frac))
        else frac.map { DIGITS[numero(it)] }.joinToString(" ")
        return cat(w, " pilkku ", f)
    }

    // ---------- yhteys: mitä luvun perässä on ----------

    /** Seuraavan sanan laji: loppu, tuntematon, ei-nomini tai nomini luentoineen (luku, sija). */
    private fun context(text: String, end: Int): Konteksti {
        val perassa = text.substring(end)
        val word = SANA_ALUSSA.alusta(perassa)?.group(1)
        if (word == null) {
            val rest = perassa.trimStart(' ', ' ', ' ')
            if (rest.isEmpty() || rest[0] in ",;!?)]}\"'”/…") return Konteksti("loppu")
            // "3." tekstin lopussa on luku; "3. Hän" ja "3. kerros" voivat olla järjestyslukuja.
            if (rest[0] == '.' && rest.substring(1).pyStrip().isEmpty()) return Konteksti("loppu")
            return Konteksti("tuntematon")
        }
        if (word[0].isUpperCase()) return Konteksti("tuntematon")
        val mask = sijat.get(word) ?: return Konteksti("tuntematon")
        val readings = (0 until 28).filter { mask shr it and 1 == 1 }
            .map { NUMS[it / 14] to CASES[it % 14] }.toSet()
        return if (readings.isNotEmpty()) Konteksti("nomini", readings) else Konteksti("ei-nomini")
    }

    private fun negated(text: String, start: Int): Boolean {
        val clause = text.substring(0, start).split(VIRKKEEN_RAJA).last()
        return SANA.findAll(clause).any { it.value.lowercase(Locale.ROOT) in NEGATION }
    }

    /**
     * Onko luvun lauseessa (ennen tai jälkeen: 3 koiraa rakastava) verbi, jonka objekti on aina
     * partitiivissa. Luvun perässä oleva sana itse ohitetaan, koska laskettava sana voi olla
     * verbin kanssa samannäköinen (3 seuraa, 3 uhkaa).
     */
    private fun partitiveVerb(text: String, start: Int, end: Int): Boolean {
        val after = text.substring(end).replaceFirst(SANA_POIS_ALUSTA, "")
        val clause = text.substring(0, start).split(VIRKKEEN_RAJA).last() + " " +
            after.split(VIRKKEEN_RAJA).first()
        return SANA.findAll(clause.lowercase(Locale.ROOT)).any {
            it.value in partitiveVerbs || it.value.replace(CLITIC, "") in partitiveVerbs
        }
    }

    /**
     * Perusluvun sija seuraavan sanan perusteella, tai null jos epävarma.
     * n == null tarkoittaa desimaalilukua (vain perusmuoto sallitaan).
     */
    private fun cardinalCase(n: Long?, text: String, start: Int, end: Int): String? {
        val k = context(text, end)
        if (k.laji == "loppu" || k.laji == "ei-nomini") return "Sg+Nom"
        if (k.laji != "nomini") return null
        val cases = HashSet<String?>()
        for ((num, case) in k.luennat!!) {
            if (case == "Par" && num == "Sg") {
                // Partitiivisubstantiivin edellä luku on perusmuodossa (1 347 osallistujaa),
                // paitsi kiellon jälkeen ja partitiiviverbin objektina: silloin ei arvata.
                // Kesto ja kerta (odotin 3 tuntia) ovat ajanilmauksia, joissa perusmuoto pätee.
                val word = SANA_ALUSSA_PAKKO.alusta(text.substring(end))!!.group(1)
                val governed = partitiveVerb(text, start, end) &&
                    word.lowercase(Locale.ROOT) !in measureWords
                cases.add(if (n == 1L || negated(text, start) || governed) null else "Sg+Nom")
            } else if (case == "Nom") {
                cases.add("Sg+Nom")
            } else if (num == "Sg") {
                cases.add("Sg+$case")
            } else {
                cases.add(null) // monikko: kolmet housut, ei tueta
            }
        }
        if (cases.size != 1) return null
        val case = cases.first()
        if (n == null && case != "Sg+Nom") return null
        return case
    }

    // ---------- passit ----------

    private fun sign(s: String?) = if (s != null) "miinus " else ""

    private fun acronym(m: MatchResult, @Suppress("UNUSED_PARAMETER") text: String): Korvaus {
        val raw = m.group(1)
        var suffix = m.group(2) ?: ""
        ACRONYM_WORD[raw]?.let { return Korvaus(it + suffix) }
        val names = raw.map { LETTER_NAMES.getValue(it) }.toMutableList()
        val stem = LETTER_STEMS[names.last()]
        if (suffix.isNotEmpty() && stem != null) {
            // Takavokaalinen pääte (THL:ssa) muutetaan ä-vartaloon sopivaksi: ällässä.
            suffix = suffix.replace('a', 'ä').replace('o', 'ö').replace('u', 'y')
            // Partitiivi kirjoitetaan usein t-alkuisena (THL:tä), mutta muoto on ällää.
            if (suffix == "tä" || suffix == "ttä") suffix = "ää"
            names[names.lastIndex] = if (suffix[0] in "aeiouyäö") stem.dropLast(1) + suffix else stem + suffix
            suffix = ""
        }
        return Korvaus(names.joinToString(" ") + suffix)
    }

    private fun abbr(m: MatchResult, text: String): String? {
        val raw = m.group(1)
        var (word, cond) = ABBR.getValue(raw.lowercase(Locale.ROOT))
        val after = text.substring(m.end())
        if (cond == "num" && LUKU_PERASSA.alusta(after) == null) return null
        if (cond == "vuosi" && VUOSI_PERASSA.alusta(after) == null) return null
        if (raw[0].isUpperCase()) word = word[0].uppercaseChar() + word.substring(1)
        if (raw.lowercase(Locale.ROOT) in SENTENCE_FINAL_ABBR &&
            (after.pyStrip().isEmpty() || ISO_SANA_PERASSA.alusta(after) != null)) word += "."
        return word
    }

    private fun phone(m: MatchResult): String? {
        val rest = m.group(3).trim(' ', '-').split(Regex("[ -]"))
        val groups = if (m.group(1).startsWith("+358")) listOf("+358", m.group(2)) + rest
        else listOf(m.group(1) + m.group(2)) + rest
        val digits = groups.joinToString("").replace("+", "")
        if (digits.length !in 7..13) return null
        fun say(g: String) = (if (g.startsWith("+")) "plus " else "") +
            g.trimStart('+').map { DIGITS[numero(it)] }.joinToString(" ")
        // Ryhmien väliin pilkku: syntetisaattori pitää tauon kuten numeroa sanellessa.
        return groups.joinToString(", ") { say(it) }
    }

    private fun date(m: MatchResult, text: String): String? {
        val d = luku(m.group(1))
        val mo = luku(m.group(2))
        val y = m.group(3)
        if (d !in 1L..31L || mo !in 1L..12L) return null
        var out = cat(ordinal(d), " ", MONTHS[(mo - 1).toInt()]) ?: return null
        if (y != null) {
            out += " " + (card(luku(y)) ?: return null)
        } else if (text.substring(m.end()).pyStrip().isEmpty()) {
            // "25.10." : toinen piste on päiväyksen osa, mutta jos virke päättyy, se jää.
            out += "."
        }
        return out
    }

    private fun dateNamed(m: MatchResult): String? {
        // Päivä on perusmuodossa kuten numeropäiväyksessä (kahdeskymmenesviides lokakuuta).
        val d = luku(m.group(1))
        val y = m.group(3)
        if (d !in 1L..31L) return null
        var out = cat(ordinal(d), " ", m.group(2)) ?: return null
        if (y != null) out += " " + (card(luku(y)) ?: return null)
        return out
    }

    private fun time(m: MatchResult): String? {
        val word = m.group(1)
        fun clock(h0: String, mi: String?, case: String): String? {
            val h = luku(h0)
            if (h > 24 || (mi != null && luku(mi) > 59)) return null
            var s = card(h, case) ?: return null
            if (mi != null && luku(mi) != 0L) {
                if (mi.startsWith("0")) {
                    if (case != "Sg+Nom") return null
                    s += " nolla " + DIGITS[numero(mi[1])]
                } else {
                    s += " " + (card(luku(mi), case) ?: return null)
                }
            }
            return s
        }
        if (m.group(4) == null) {
            val t = clock(m.group(2), m.group(3), "Sg+Nom") ?: return null
            return "$word $t"
        }
        val a = clock(m.group(2), m.group(3), "Sg+Ela") ?: return null
        val b = clock(m.group(4), m.group(5), "Sg+Ill") ?: return null
        return "$word $a $b"
    }

    private fun money(m: MatchResult, text: String): Korvaus? {
        val e = luku(m.group(2))
        val cents = m.group(3)
        val c = if (cents == null || cents == "-") 0L else luku(cents)
        val parts = ArrayList<String>()
        if (e > 0 || c == 0L) {
            val w = card(e) ?: return null // yli biljoona: lukusanaa ei ole
            parts += if (e == 1L) "yksi euro" else "$w euroa"
        }
        if (c > 0) parts += if (c == 1L) "yksi sentti" else "${card(c) ?: return null} senttiä"
        val (tail, consumed) = per(text, m.end())
        return Korvaus(sign(m.group(1)) + parts.joinToString(" ") + tail, consumed)
    }

    /** Per-yksikkö luvun ja yksikön perässä (€/kk -> " kuukaudessa"), muuten ("", 0). */
    private fun per(text: String, end: Int): Pair<String, Int> {
        val p = PER.alusta(text.substring(end)) ?: return "" to 0
        return " " + words.getValue(units.getValue(p.group(1))).getValue("Sg+Ine") to p.end()
    }

    private fun range(m: MatchResult, text: String): Korvaus? {
        val a = luku(m.group(1))
        val b = luku(m.group(2))
        if (a >= b) return null // 3-2 on tulos eikä väli
        val after = text.substring(m.end())
        val unit = rangeUnit.alusta(after)
        var tail = ""
        var consumed = 0
        if (unit != null) {
            tail = " " + words.getValue(units.getValue(unit.group(1))).getValue("Sg+Par")
            consumed = unit.end()
        } else if (cardinalCase(null, text, m.start(), m.end()) != "Sg+Nom") {
            return null
        }
        val x = card(a, "Sg+Ela") ?: return null
        val y = card(b, "Sg+Ill") ?: return null
        return Korvaus("$x $y$tail", consumed)
    }

    private fun unit(m: MatchResult, text: String): Korvaus? {
        val frac = m.group(3)
        val par = words.getValue(units.getValue(m.group(4)))
        val suffix = m.group(5)
        val case = if (suffix != null) suffixCase(suffix, par) ?: return null else "Sg+Nom"
        val n = luku(m.group(2))
        val num: String?
        val noun: String?
        var tail = "" to 0
        if (case == "Sg+Nom" || case == "Sg+Par") {
            num = if (frac != null) decimal(n, frac) else card(n)
            noun = if (n == 1L && frac == null) par["Sg+Nom"] else par["Sg+Par"]
            if (suffix == null) tail = per(text, m.end())
        } else {
            if (frac != null) return null // 3,5 metrin: osien taivutusta ei ole todennettu
            num = card(n, case)
            noun = par[case]
        }
        if (num.isNullOrEmpty() || noun.isNullOrEmpty()) return null
        return Korvaus("${sign(m.group(1))}$num $noun${tail.first}", tail.second)
    }

    private fun suffixCase(suffix: String, paradigm: Map<String, String>): String? {
        for ((pattern, case) in SUFFIX_CASE) {
            val form = paradigm["Sg+$case"]
            if (pattern.matches(suffix) && !form.isNullOrEmpty() && form.endsWith(suffix)) return "Sg+$case"
        }
        return null
    }

    private fun slash(m: MatchResult): String? {
        val a = m.group(1)
        val b = m.group(2)
        if (listOf(a, b).any { it.length > 1 && it.startsWith("0") }) return null // 01/02: etunolla, ei arvata
        val x = card(luku(a)) ?: return null
        val y = card(luku(b)) ?: return null
        return "$x kautta $y"
    }

    private fun hyphen(m: MatchResult): String? {
        // 1990-luvulla, 5-vuotias: yhdyssanan alkuosa on aina perusmuodossa.
        // Viiva korvataan välilyönnillä, koska syntetisaattori lukee sen "viivana".
        val num = card(luku(m.group(1))) ?: return null
        return "$num ${m.group(2)}"
    }

    private fun colon(m: MatchResult): String? {
        val n = luku(m.group(1))
        val suffix = m.group(2)
        val forms = LinkedHashMap<String, String>()
        for (c in CASES.take(11)) card(n, "Sg+$c")?.let { forms["Sg+$c"] = it }
        suffixCase(suffix, forms)?.let { return card(n, it) }
        // Järjestysluku: 5:s, 5:nnen, 5:nteen, 2:sen. Lyhyt pääte (5:a) ei riitä
        // erottamaan sijaa, joten vaaditaan s, n-alku tai vähintään kolme kirjainta.
        if (suffix == "s" || suffix.startsWith("n") || suffix.length >= 3) {
            val hits = ordAtoms.getValue("1").keys.filter { it.startsWith("Sg") }
                .mapNotNull { ordinal(n, it) }.filter { it.endsWith(suffix) }.toSet()
            if (hits.size == 1) return hits.first()
        }
        return null
    }

    private fun ordinalPass(m: MatchResult, text: String): String? {
        val n = luku(m.group(1))
        // Kuukauden edellä luku on päiväys, jonka dateNamed on jo ratkaissut tai hylännyt.
        if (KUUKAUSI_PERASSA.alusta(text.substring(m.end())) != null) return null
        val k = context(text, m.end())
        if (k.laji != "nomini") return null
        val forms = k.luennat!!.map { (num, case) -> ordinal(n, "$num+$case") }.toSet()
        if (forms.size != 1) return null
        return forms.first()
    }

    private fun decimalPass(m: MatchResult, text: String): String? {
        if (cardinalCase(null, text, m.start(), m.end()) != "Sg+Nom") return null
        val d = decimal(luku(m.group(2)), m.group(3)) ?: return null
        return sign(m.group(1)) + d
    }

    private fun integer(m: MatchResult, text: String): String? {
        val digits = m.group(2).filter { it.isDigit() }
        if (digits.length > 1 && digits.startsWith("0")) return null
        val n = luku(digits)
        // Kuukauden edellä luku on päiväys. Jos dateNamed hylkäsi sen (32 heinäkuuta), ei arvata.
        if (KUUKAUSI_PERASSA.alusta(text.substring(m.end())) != null) return null
        val case = cardinalCase(n, text, m.start(), m.end()) ?: return null
        val word = card(n, case)
        if (word.isNullOrEmpty()) return null
        return sign(m.group(1)) + word
    }

    // ---------- ajo ----------

    fun avaa(input: String): String {
        var text = input
        for ((regex, fn) in passes) {
            val out = StringBuilder()
            var pos = 0
            val matcher = regex.matcher(text)
            while (matcher.find()) {
                if (matcher.start() < pos) continue
                val res = fn(matcher.toMatchResult(), text) ?: continue
                out.append(text, pos, matcher.start())
                out.append(res.teksti)
                pos = matcher.end() + res.kulutettu
            }
            out.append(text, pos, text.length)
            text = out.toString()
        }
        return text
    }
}
