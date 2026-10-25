"""Ajoaikamittaus: avauskerroksen käsittelyaika lausetta kohden.

Mittaa (1) taulukoiden latausajan kerran käynnistyksessä ja (2) avaa()-kutsun keston
jokaiselle testilauseelle 2000 toistolla. Raportoi mediaanin ja hitaimman toiston.
Tulos: build/tyo/ajat.json (ei versioida) ja konsolituloste.
Ajo: python -X utf8 tests/mittaa.py
"""
import json
import statistics
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "addon" / "globalPlugins" / "lausu"))

t0 = time.perf_counter()
from avaa import Avaaja  # noqa: E402
a = Avaaja()
load_s = time.perf_counter() - t0

rivit = [r.split("\t", 1) for r in (HERE / "lauseet.txt").read_text(encoding="utf-8").splitlines()
         if "\t" in r]
TOISTOT = 2000
tulos = {"lataus_s": round(load_s, 2), "toistot": TOISTOT, "lauseet": []}
for tunnus, teksti in rivit:
    ajat = []
    for _ in range(TOISTOT):
        s = time.perf_counter_ns()
        a.avaa(teksti)
        ajat.append(time.perf_counter_ns() - s)
    med, mx = statistics.median(ajat) / 1000, max(ajat) / 1000
    tulos["lauseet"].append({"tunnus": tunnus, "merkkejä": len(teksti),
                             "mediaani_us": round(med, 1), "hitain_us": round(mx, 1)})
    print(f"{tunnus:13} {len(teksti):4} merkkiä  mediaani {med:7.1f} µs  hitain {mx:8.1f} µs")
kaikki = [l["mediaani_us"] for l in tulos["lauseet"]]
tulos["mediaanien_mediaani_us"] = round(statistics.median(kaikki), 1)
tulos["mediaanien_max_us"] = max(kaikki)
print(f"taulukoiden lataus {load_s:.2f} s (kerran käynnistyksessä)")
print(f"lauseen mediaaniaika {tulos['mediaanien_mediaani_us']} µs, hitain lause {tulos['mediaanien_max_us']} µs")
out = HERE.parent / "build" / "tyo" / "ajat.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(tulos, ensure_ascii=False, indent=1), encoding="utf-8")
