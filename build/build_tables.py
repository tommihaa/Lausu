# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Tommi Haanranta
"""Käännösvaihe 1: tuottaa avauskerroksen hakutaulukot.

1. addon/globalPlugins/lausu/data/lukusanat.json: lukusanojen perusosien (yksi..miljardi, ensimmäinen..miljoonas)
   ja yksikkösanojen (kilometri, prosentti...) taivutusmuodot GiellaLT/omorfi-generaattorilta
   (uralicNLP), kuten Itun gen_wordforms.py tekee sanastolleen.
2. build/tyo/sijat.pkl (välitulos, ei versioida; rakenna_sanapuu.py pakkaa sen): sanamuoto -> sija- ja lukutieto. Johdetaan Itun valmiista
   SanaMix/data/wordforms.txt:stä (luetaan, ei muokata). Koodi on FST:n oma analyysi,
   joten sijatieto ei ole arvaus.

Ajo: python -X utf8 build/build_tables.py   (Python, jossa uralicNLP ja fin-malli)
Ajon aikana (avaa.py) ei tarvita uralicNLP:tä: pelkkiä hakuja.
"""
import json
import pickle
import re
import sys
import time
from pathlib import Path

from uralicNLP import uralicApi

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "addon" / "globalPlugins" / "lausu" / "data"
WORK = ROOT / "build" / "tyo"
ITU_FORMS = ROOT.parent / "SanaMix" / "data" / "wordforms.txt"  # Itu, vain luku

CASES = ["Nom", "Gen", "Par", "Ess", "Tra", "Ine", "Ela", "Ill",
         "Ade", "Abl", "All", "Abe", "Ins", "Com"]
NUMS = ["Sg", "Pl"]

CARD = {1: "yksi", 2: "kaksi", 3: "kolme", 4: "neljä", 5: "viisi", 6: "kuusi",
        7: "seitsemän", 8: "kahdeksan", 9: "yhdeksän", 10: "kymmenen",
        100: "sata", 1000: "tuhat", 10**6: "miljoona", 10**9: "miljardi"}
# Järjestysluvut. "yhdes" ja "kahdes" ovat yhdysosamuotoja (yhdestoista,
# kahdeskymmenes); luvun lopussa ovat ensimmäinen ja toinen.
ORD = {1: "ensimmäinen", 2: "toinen", 3: "kolmas", 4: "neljäs", 5: "viides",
       6: "kuudes", 7: "seitsemäs", 8: "kahdeksas", 9: "yhdeksäs", 10: "kymmenes",
       100: "sadas", 1000: "tuhannes", 10**6: "miljoonas", "yhdes": "yhdes", "kahdes": "kahdes"}

# Yksiköt: lyhenne -> lemma. Muodot generoidaan, jotta "km:n" -> kilometrin.
UNITS = {"km": "kilometri", "m": "metri", "cm": "senttimetri", "mm": "millimetri",
         "kg": "kilogramma", "g": "gramma", "l": "litra", "dl": "desilitra",
         "h": "tunti", "min": "minuutti", "s": "sekunti", "%": "prosentti",
         "°C": "aste", "°": "aste", "kpl": "kappale", "€": "euro", "snt": "sentti",
         "kWh": "kilowattitunti"}
# Muut taivutettavat apusanat.
WORDS = ["euro", "sentti", "sivu", "kello", "prosentti", "nolla"]

# Verbit, joiden objekti on aina partitiivissa (rakastan kolmea koiraa). Lauseessa, jossa
# jokin näistä on, luku + partitiivisana jätetään ennalleen, koska perusmuoto olisi väärä.
# Mukana vain verbit, joilla tulosobjektia (kolme) ei käytetä: katsoa, hoitaa ja käsitellä
# puuttuvat, koska "katsoin 3 elokuvaa" voi olla kumpaa tahansa.
PARTITIVE_VERBS = [
    "rakastaa", "vihata", "inhota", "pelätä", "odottaa", "auttaa", "kiittää", "onnitella",
    "ajatella", "miettiä", "pohtia", "ihailla", "kunnioittaa", "arvostaa", "halata", "suudella",
    "koskettaa", "katsella", "kuunnella", "etsiä", "tukea", "vastustaa", "puolustaa", "uhata",
    "häiritä", "kohdella", "palvella", "seurata", "edustaa", "kaivata", "ikävöidä", "sääliä",
    "kiusata", "tervehtiä", "lohduttaa", "rohkaista", "jahdata", "muistella", "harrastaa",
    "rangaista", "johtaa", "opettaa",
]
VERB_TAGS = (
    [f"Act+Ind+{t}+{p}" for t in ("Prs", "Prt") for p in ("Sg1", "Sg2", "Sg3", "Pl1", "Pl2", "Pl3")]
    + [f"Act+Cond+{p}" for p in ("Sg1", "Sg2", "Sg3", "Pl1", "Pl2", "Pl3")]
    + ["Act+Ind+Prs+ConNeg", "Act+Cond+ConNeg", "Act+Imprt+ConNeg", "Act+Imprt+Sg2",
       "Act+Imprt+Sg3", "Act+Imprt+Pl1", "Act+Imprt+Pl2", "Act+Imprt+Pl3", "Act+Pot+Sg3",
       "Pss+Ind+Prs+Pe4", "Pss+Ind+Prt+Pe4", "Pss+Cond+Pe4", "Act+InfA+Sg+Lat",
       "Act+InfE+Sg+Ine", "Act+InfE+Sg+Ins"]
    + [f"Act+InfMa+Sg+{c}" for c in ("Ill", "Ine", "Ela", "Ade", "Abe")]
    + [f"{p}+{n}+{c}" for p in ("Act+PrsPrc", "Act+PrfPrc", "Pss+PrsPrc", "Pss+PrfPrc", "AgPrc")
       for n in NUMS for c in ("Nom", "Gen", "Par")]
)
# Kesto- ja kertasanat: "odotin 3 tuntia" on ajanilmaus, jossa luku on perusmuodossa
# partitiiviverbistä riippumatta. Näiden edellä luku avataan kuten ennenkin.
MEASURE_NOUNS = ["sekunti", "minuutti", "tunti", "päivä", "viikko", "kuukausi", "vuosi",
                 "vuorokausi", "vuosikymmen", "vuosisata", "kerta", "hetki", "ilta", "yö", "aamu"]


def gen(analysis: str) -> str | None:
    res = uralicApi.generate(analysis, "fin")
    return res[0][0] if res else None


def paradigm(lemma: str, pos: str, nums=NUMS) -> dict:
    out = {}
    for n in nums:
        for c in CASES:
            f = gen(f"{lemma}+{pos}+{n}+{c}")
            if f:
                out[f"{n}+{c}"] = f
    return out


def build_numerals() -> dict:
    t = time.time()
    data = {
        "card": {str(k): paradigm(v, "Num+Card", ["Sg"]) for k, v in CARD.items()},
        "ord": {str(k): paradigm(v, "A+Ord") for k, v in ORD.items()},
        "units": {u: l for u, l in UNITS.items()},
        # Vain yksikkö: monikossa generaattori antaa ensimmäisenä arkaaisia
        # rinnakkaismuotoja (astehitten), eikä kerros tarvitse niitä.
        "words": {l: paradigm(l, "N", ["Sg"]) for l in set(UNITS.values()) | set(WORDS)},
        "partitiiviverbit": sorted({f for v in PARTITIVE_VERBS for t in VERB_TAGS
                                    if (f := gen(f"{v}+V+{t}"))}),
        "mittasanat": sorted({f for n in MEASURE_NOUNS if (f := gen(f"{n}+N+Sg+Par"))}),
    }
    # Jokaisesta verbistä on saatava ainakin preesens, muuten lemma on kirjoitettu väärin.
    for v in PARTITIVE_VERBS:
        assert gen(f"{v}+V+Act+Ind+Prs+Sg1"), v
    assert len(data["mittasanat"]) == len(MEASURE_NOUNS), data["mittasanat"]
    # Tarkistus: jokaisella perusosalla on oltava perusmuoto, muuten taulukko on rikki.
    for kind in ("card", "ord"):
        for k, p in data[kind].items():
            assert "Sg+Nom" in p, (kind, k, p)
    print(f"lukusanat: {time.time() - t:.1f} s", file=sys.stderr)
    return data


# Nominaalinen analyysi: koodin loppu on +luku+sija (N, A, partisiipit, -minen).
NOMINAL = re.compile(r"(?:^|\+)(Sg|Pl)\+(" + "|".join(CASES) + r")$")


def build_case_table() -> dict:
    """muoto -> bittimaski. Bitti num*14+sija = nominaalinen luenta,
    bitti 28 = sana tunnetaan (vaikka sillä ei olisi nominaalista luentaa)."""
    t = time.time()
    table: dict[str, int] = {}
    with open(ITU_FORMS, encoding="utf-8") as f:
        for line in f:
            form, _, rest = line.rstrip("\n").partition("\t")
            mask = 1 << 28
            for lem in rest.split("|"):
                _, _, codes = lem.partition("#")
                for code in codes.split(";"):
                    m = NOMINAL.search(code)
                    if m and not code.startswith("V+Act+Inf"):
                        mask |= 1 << (NUMS.index(m.group(1)) * 14 + CASES.index(m.group(2)))
            table[form] = mask
    print(f"sijataulukko: {len(table)} muotoa, {time.time() - t:.1f} s", file=sys.stderr)
    return table


def main() -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    nums = build_numerals()
    (DATA / "lukusanat.json").write_text(
        json.dumps(nums, ensure_ascii=False, indent=1), encoding="utf-8")
    table = build_case_table()
    with open(WORK / "sijat.pkl", "wb") as f:
        pickle.dump(table, f, protocol=pickle.HIGHEST_PROTOCOL)


if __name__ == "__main__":
    main()
