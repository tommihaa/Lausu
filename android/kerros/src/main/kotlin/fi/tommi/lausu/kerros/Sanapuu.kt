// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Tommi Haanranta
package fi.tommi.lausu.kerros

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sijataulukon pakattu muoto: minimoitu sanapuu (DAWG), jossa sijatieto on sanan perässä.
 * Kotlin-vastine tiedostolle addon/globalPlugins/lausu/sanapuu.py, sama tiedostomuoto.
 *
 * Avain on sanamuoto + erotin + luentaluokan tunnus. Rakennus: build/rakenna_sanapuu.py.
 * Ajon aikana: Sanapuu.lataa(tavut).get(sana) -> bittimaski tai null.
 */
class Sanapuu private constructor(
    alphabet: String,
    private val masks: IntArray,
    private val first: IntArray,
    private val chars: ByteArray,
    private val targets: IntArray,
) {
    private val code = HashMap<Char, Int>().apply { alphabet.forEachIndexed { i, c -> put(c, i + 1) } }
    private val nlet = alphabet.length + 1

    companion object {
        private val MAGIC = "SIJAPUU1".toByteArray(Charsets.US_ASCII)
        private const val SEP = 0 // erotinmerkin koodi; kirjaimet alkavat 1:stä, luokkatunnukset aakkoston jälkeen

        fun lataa(data: ByteArray): Sanapuu {
            require(data.size >= 24 && data.copyOfRange(0, 8).contentEquals(MAGIC)) { "väärä tiedostomuoto" }
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            b.position(8)
            val alen = b.int
            val mlen = b.int
            val nnodes = b.int
            val nedges = b.int
            val alphabet = String(data, b.position(), alen, Charsets.UTF_8)
            b.position(b.position() + alen)
            val masks = IntArray(mlen) { b.int }
            val first = IntArray(nnodes + 1) { b.int }
            val chars = ByteArray(nedges).also { b.get(it) }
            val targets = IntArray(nedges) { b.int }
            return Sanapuu(alphabet, masks, first, chars, targets)
        }
    }

    private fun step(node: Int, c: Int): Int {
        var lo = first[node]
        var hi = first[node + 1]
        while (lo < hi) { // reunat ovat merkin mukaan järjestyksessä
            val mid = (lo + hi) ushr 1
            val m = chars[mid].toInt() and 0xFF
            when {
                m < c -> lo = mid + 1
                m > c -> hi = mid
                else -> return targets[mid]
            }
        }
        return -1
    }

    fun get(word: String): Int? {
        var node = 0
        for (ch in word) {
            val c = code[ch] ?: return null
            node = step(node, c)
            if (node < 0) return null
        }
        node = step(node, SEP)
        if (node < 0) return null
        // Erottimen jälkeen on tasan yksi reuna: luokkatunnus.
        val i = first[node]
        return masks[(chars[i].toInt() and 0xFF) - nlet]
    }
}
