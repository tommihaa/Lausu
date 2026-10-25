# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Tommi Haanranta
"""Sijataulukon pakattu muoto: minimoitu sanapuu (DAWG), jossa sijatieto on sanan perässä.

Avain on sanamuoto + erotin + luentaluokan tunnus (83 eri sija- ja lukuyhdistelmää). Koska
samaan sijaan taipuneet sanat päättyvät samoin, puun loppuosat yhdistyvät ja tieto mahtuu
pieneen tilaan. Rakenne on puhdasta Pythonia ja ladataan array-taulukoiksi, joten se toimii
myös NVDA:n Pythonissa ilman käännettyjä riippuvuuksia.

Rakennus: build/rakenna_sanapuu.py. Ajon aikana: Sanapuu.lataa(polku).get(sana) -> bittimaski tai None.
"""
import struct
from array import array
from pathlib import Path

MAGIC = b"SIJAPUU1"
SEP = 0          # erotinmerkin koodi; kirjaimet alkavat 1:stä, luokkatunnukset aakkoston jälkeen


class Sanapuu:
    def __init__(self, alphabet: str, masks: list[int], first: array, chars: array, targets: array):
        self.code = {c: i + 1 for i, c in enumerate(alphabet)}
        self.nlet = len(alphabet) + 1
        self.masks = masks
        self.first, self.chars, self.targets = first, chars, targets

    @classmethod
    def lataa(cls, path: Path) -> "Sanapuu":
        data = path.read_bytes()
        if data[:8] != MAGIC:
            raise ValueError("väärä tiedostomuoto")
        pos = 8
        alen, mlen, nnodes, nedges = struct.unpack_from("<IIII", data, pos)
        pos += 16
        alphabet = data[pos:pos + alen].decode("utf-8")
        pos += alen
        masks = list(struct.unpack_from(f"<{mlen}I", data, pos))
        pos += 4 * mlen
        first = array("I"); first.frombytes(data[pos:pos + 4 * (nnodes + 1)]); pos += 4 * (nnodes + 1)
        chars = array("B"); chars.frombytes(data[pos:pos + nedges]); pos += nedges
        targets = array("I"); targets.frombytes(data[pos:pos + 4 * nedges])
        return cls(alphabet, masks, first, chars, targets)

    def _step(self, node: int, c: int) -> int:
        first, chars = self.first, self.chars
        lo, hi = first[node], first[node + 1]
        while lo < hi:                      # reunat ovat merkin mukaan järjestyksessä
            mid = (lo + hi) >> 1
            m = chars[mid]
            if m < c:
                lo = mid + 1
            elif m > c:
                hi = mid
            else:
                return self.targets[mid]
        return -1

    def get(self, word: str) -> int | None:
        node = 0
        code = self.code
        for ch in word:
            c = code.get(ch)
            if c is None:
                return None
            node = self._step(node, c)
            if node < 0:
                return None
        node = self._step(node, SEP)
        if node < 0:
            return None
        # Erottimen jälkeen on tasan yksi reuna: luokkatunnus.
        i = self.first[node]
        return self.masks[self.chars[i] - self.nlet]
