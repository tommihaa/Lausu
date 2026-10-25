# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Tommi Haanranta
"""Käännösvaihe 2: pakkaa sijataulukon (build/tyo/sijat.pkl) sanapuuksi
addon/globalPlugins/lausu/data/sijat.dawg ja todentaa tuloksen koko taulukkoa vasten.

Ajo: python -X utf8 build/rakenna_sanapuu.py   (build_tables.py ensin)
"""
import pickle
import struct
import sys
import time
from array import array
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "addon" / "globalPlugins" / "lausu" / "data"
WORK = ROOT / "build" / "tyo"
sys.path.insert(0, str(DATA.parent))
from sanapuu import MAGIC, SEP, Sanapuu  # noqa: E402


def build(table: dict[str, int]) -> bytes:
    alphabet = "".join(sorted({c for w in table for c in w}))
    code = {c: i + 1 for i, c in enumerate(alphabet)}
    masks = sorted(set(table.values()))
    mcode = {m: len(alphabet) + 1 + i for i, m in enumerate(masks)}
    if len(alphabet) + 1 + len(masks) > 255:
        raise ValueError("merkit eivät mahdu tavuun")

    # Daciukin ym. inkrementaalinen minimointi järjestetyistä avaimista.
    keys = sorted(tuple(code[c] for c in w) + (SEP, mcode[m]) for w, m in table.items())
    # Solmut sanakirjassa, jotta korvatut (päällekkäiset) solmut voi vapauttaa heti:
    # muuten rakennus pitäisi muistissa kymmeniä miljoonia tilapäisiä solmuja.
    children: dict[int, dict[int, int]] = {0: {}}
    final: set[int] = set()
    register: dict[tuple, int] = {}
    next_id = 1

    def signature(n: int) -> tuple:
        return (n in final, tuple(sorted(children[n].items())))

    def replace_or_register(n: int) -> None:
        # Iteratiivinen: kuljetaan viimeisten lasten polku alas ja käsitellään alhaalta ylös.
        path = [n]
        while True:
            ch = children[path[-1]]
            if not ch:
                break
            last = ch[max(ch)]
            path.append(last)
        for parent, child in zip(reversed(path[:-1]), reversed(path[1:])):
            sig = signature(child)
            existing = register.get(sig)
            if existing is not None and existing != child:
                children[parent][max(children[parent])] = existing
                del children[child]
                final.discard(child)
            else:
                register[sig] = child

    for k in keys:
        n, i = 0, 0
        while i < len(k) and k[i] in children[n]:
            n = children[n][k[i]]
            i += 1
        if children[n]:
            replace_or_register(n)
        for c in k[i:]:
            children[next_id] = {}
            children[n][c] = next_id
            n = next_id
            next_id += 1
        final.add(n)
    replace_or_register(0)

    # Numeroidaan saavutettavat solmut leveyssuunnassa ja litistetään.
    order, index = [0], {0: 0}
    for n in order:
        for c in sorted(children[n]):
            t = children[n][c]
            if t not in index:
                index[t] = len(order)
                order.append(t)
    first, chars, targets = array("I"), array("B"), array("I")
    for n in order:
        first.append(len(chars))
        for c in sorted(children[n]):
            chars.append(c)
            targets.append(index[children[n][c]])
    first.append(len(chars))
    ab = alphabet.encode("utf-8")
    out = MAGIC + struct.pack("<IIII", len(ab), len(masks), len(order), len(chars)) + ab
    out += struct.pack(f"<{len(masks)}I", *masks)
    return out + first.tobytes() + chars.tobytes() + targets.tobytes()


def main() -> None:
    t = time.time()
    with open(WORK / "sijat.pkl", "rb") as f:
        table = pickle.load(f)
    blob = build(table)
    (DATA / "sijat.dawg").write_bytes(blob)
    # Todennus: puun on annettava sama tulos kuin taulukolla jokaiselle muodolle.
    puu = Sanapuu.lataa(DATA / "sijat.dawg")
    bad = sum(1 for w, m in table.items() if puu.get(w) != m)
    if bad:
        raise SystemExit(f"sanapuu antaa väärän tuloksen {bad} muodolle")
    print(f"sanapuu: {len(table)} avainta, {len(blob) / 1e6:.2f} Mt, {time.time() - t:.0f} s", file=sys.stderr)


if __name__ == "__main__":
    main()
