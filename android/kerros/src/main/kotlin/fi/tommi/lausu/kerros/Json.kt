// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Tommi Haanranta
package fi.tommi.lausu.kerros

/**
 * Pieni JSON-lukija lukusanat.json-tiedostolle. Kerros on ilman riippuvuuksia, eikä Androidin
 * org.json ole käytettävissä JVM-testeissä. Oliot säilyttävät avainten järjestyksen, koska
 * yksikkölistan järjestys vaikuttaa säännöllisen lausekkeen vaihtoehtojen järjestykseen.
 */
internal class Json private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun lue(teksti: String): Any? = Json(teksti).run {
            val arvo = arvo()
            tyhja()
            require(i == s.length) { "ylimääräistä tekstiä kohdassa $i" }
            arvo
        }
    }

    private fun tyhja() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun arvo(): Any? {
        tyhja()
        return when (val c = s[i]) {
            '{' -> olio()
            '[' -> lista()
            '"' -> merkkijono()
            't' -> sana("true", true)
            'f' -> sana("false", false)
            'n' -> sana("null", null)
            else -> if (c == '-' || c.isDigit()) luku() else error("odottamaton merkki '$c' kohdassa $i")
        }
    }

    private fun sana(sana: String, arvo: Any?): Any? {
        require(s.startsWith(sana, i)) { "odotettiin $sana kohdassa $i" }
        i += sana.length
        return arvo
    }

    private fun olio(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        i++
        tyhja()
        if (s[i] == '}') { i++; return out }
        while (true) {
            tyhja()
            val avain = merkkijono()
            tyhja()
            require(s[i] == ':') { "odotettiin ':' kohdassa $i" }
            i++
            out[avain] = arvo()
            tyhja()
            when (s[i++]) {
                ',' -> continue
                '}' -> return out
                else -> error("odotettiin ',' tai '}' kohdassa ${i - 1}")
            }
        }
    }

    private fun lista(): List<Any?> {
        val out = ArrayList<Any?>()
        i++
        tyhja()
        if (s[i] == ']') { i++; return out }
        while (true) {
            out += arvo()
            tyhja()
            when (s[i++]) {
                ',' -> continue
                ']' -> return out
                else -> error("odotettiin ',' tai ']' kohdassa ${i - 1}")
            }
        }
    }

    private fun merkkijono(): String {
        require(s[i] == '"') { "odotettiin merkkijonoa kohdassa $i" }
        i++
        val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> error("tuntematon pakomerkki '\\$e' kohdassa ${i - 1}")
                }
                else -> sb.append(c)
            }
        }
    }

    private fun luku(): Number {
        val alku = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(alku, i)
        return t.toLongOrNull() ?: t.toDouble()
    }
}
