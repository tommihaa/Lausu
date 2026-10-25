# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Tommi Haanranta
"""Lausu: suomen tekstin avauskerros ruudunlukua varten.

Kerros saa raakatekstin ja palauttaa tekstin, jossa luvut, lyhenteet ja vakiokuviot
on kirjoitettu auki oikeassa taivutusmuodossa. Tulos menee mille tahansa
puhesyntetisaattorille.

Periaatteet:
- Ajon aikana tehdään vain hakuja. Lukusanojen muodot ja sanamuotojen sija-analyysit
  tuotetaan käännösvaiheessa (build/build_tables.py ja build/rakenna_sanapuu.py,
  omorfi-generaattori uralicNLP:n kautta).
- Ei arvailua: jos sääntö ei tunnista tapausta varmasti, kohta jätetään ennalleen.

Käyttö:  python -X utf8 avaa.py "Juna saapuu 5:een mennessä."
"""
import json
import re
import sys
from pathlib import Path

try:
    from .sanapuu import Sanapuu     # NVDA-lisäosan paketti
except ImportError:
    from sanapuu import Sanapuu      # suora ajo ja testit

HERE = Path(__file__).resolve().parent
CASES = ["Nom", "Gen", "Par", "Ess", "Tra", "Ine", "Ela", "Ill",
         "Ade", "Abl", "All", "Abe", "Ins", "Com"]
NUMS = ["Sg", "Pl"]
KNOWN_BIT = 1 << 28

SP = "[   ]"           # välilyönti, sitova välilyönti, kapea sitova
GROUPED = rf"\d{{1,3}}(?:{SP}\d{{3}})+(?!\d)"   # 1 347, 12 000 000
INT = rf"(?:{GROUPED}|\d+)"
MINUS = "[−-]"
# Luvun alku: ei kiinni sanassa, toisessa luvussa eikä viivassa (3-2 on tulos).
NB = r"(?<![\w,.:/–—−-])"
LOWER = "a-zåäöšž"
LETTER = "A-Za-zÅÄÖåäöŠšŽžÉé"

DIGITS = ["nolla", "yksi", "kaksi", "kolme", "neljä", "viisi", "kuusi",
          "seitsemän", "kahdeksan", "yhdeksän"]
MONTHS = ["tammikuuta", "helmikuuta", "maaliskuuta", "huhtikuuta", "toukokuuta",
          "kesäkuuta", "heinäkuuta", "elokuuta", "syyskuuta", "lokakuuta",
          "marraskuuta", "joulukuuta"]

# Lyhenne -> auki kirjoitettu. Ehto: None = aina, "num" = vain luvun edellä,
# "vuosi" = vain nelinumeroisen luvun edellä.
ABBR = {
    "esim.": ("esimerkiksi", None), "ks.": ("katso", None),
    "jne.": ("ja niin edelleen", None), "mm.": ("muun muassa", None),
    "ym.": ("ynnä muuta", None), "yms.": ("ynnä muuta sellaista", None),
    "tms.": ("tai muuta sellaista", None), "ts.": ("toisin sanoen", None),
    "vrt.": ("vertaa", None), "huom.": ("huomaa", None), "puh.": ("puhelin", None),
    "klo": ("kello", None), "klo.": ("kello", None),
    "n.": ("noin", "num"), "s.": ("sivu", "num"), "nro": ("numero", "num"),
    "v.": ("vuonna", "vuosi"),
}
# Nämä voivat päättää virkkeen, jolloin piste on myös virkkeen loppu.
SENTENCE_FINAL_ABBR = {"jne.", "ym.", "yms.", "tms."}
NEGATION = {"en", "et", "ei", "emme", "ette", "eivät", "älä", "älkää"}
# Liitepartikkelit, jotka irrotetaan ennen verbihakua (rakastankin, odotatko).
CLITIC = re.compile(r"(?:kin|kaan|kään|ko|kö|han|hän|pa|pä)$")

# Kirjainlyhenteet. Sanana luettavat kirjoitetaan sanaksi (YLE -> Yle), jotta syntetisaattori
# ei tavaa tai liimaa niitä (Harri: "YLELE"). Kirjaimittain luettavat kirjoitetaan kirjainten
# nimiksi välilyönnein, koska yhdysmerkin syntetisaattori voi lukea "viivana".
# Kaksoispistepääte liitetään sellaisenaan: YLE:n -> Ylen, EU:ssa -> ee uussa.
ACRONYM_WORD = {"YLE": "Yle", "KELA": "Kela", "NATO": "Nato", "HUS": "Hus", "VTT": None}
LETTER_NAMES = {"A": "aa", "B": "bee", "C": "see", "D": "dee", "E": "ee", "F": "äf", "G": "gee",
                "H": "hoo", "I": "ii", "J": "jii", "K": "koo", "L": "äl", "M": "äm", "N": "än",
                "O": "oo", "P": "pee", "Q": "kuu", "R": "är", "S": "äs", "T": "tee", "U": "uu",
                "V": "vee", "W": "kaksoisvee", "X": "äks", "Y": "yy", "Z": "tseta",
                "Ä": "ää", "Ö": "öö"}
ACRONYM_SPELLED = {"EU", "USA", "YK", "ALV", "VR", "SAK", "EK", "THL", "HSL", "PDF", "USB", "TV", "VTT"}
ACRONYM_WORD = {k: v for k, v in ACRONYM_WORD.items() if v}

# Kaksoispistepäätteen sijat yleisimmästä alkaen. Pääte hyväksytään vain, jos
# generoitu muoto todella päättyy siihen (5:ään hylätään, koska viiteen ≠ -ään).
SUFFIX_CASE = [
    (r"(?:a|ä|ta|tä|tta|ttä)", "Par"), (r"n", "Gen"), (r"(?:na|nä)", "Ess"),
    (r"ksi", "Tra"), (r"(?:ssa|ssä)", "Ine"), (r"(?:sta|stä)", "Ela"),
    (r"(?:[aeiouyäö]{2}n|seen|hin)", "Ill"), (r"(?:lla|llä)", "Ade"),
    (r"(?:lta|ltä)", "Abl"), (r"lle", "All"),
]


class Avaaja:
    def __init__(self, data_dir: Path = HERE / "data"):
        d = json.loads((data_dir / "lukusanat.json").read_text(encoding="utf-8"))
        self.card_atoms = {int(k): v for k, v in d["card"].items()}
        self.ord_atoms = {(int(k) if k.isdigit() else k): v for k, v in d["ord"].items()}
        self.units = d["units"]
        self.words = d["words"]
        self.partitive_verbs = frozenset(d["partitiiviverbit"])
        self.measure_words = frozenset(d["mittasanat"])
        # Sijataulukko pakattuna sanapuuna (1,8 Mt levyllä, noin 5 Mt muistissa).
        self.sijat = Sanapuu.lataa(data_dir / "sijat.dawg")
        unit_alt = "|".join(re.escape(u) for u in sorted(self.units, key=len, reverse=True)
                            if u != "€")
        acr = "|".join(sorted(set(ACRONYM_WORD) | ACRONYM_SPELLED, key=len, reverse=True))
        self.passes = [
            (re.compile(rf"(?<![{LETTER}\d])({acr})(?::([{LOWER}]+))?(?![{LETTER}\d])"), self._acronym),
            (re.compile(r"(?<![\w.])(" + "|".join(re.escape(a[:-1]) + r"\." if a.endswith(".")
                        else re.escape(a) for a in sorted(ABBR, key=len, reverse=True))
                        + r")(?![\w.])", re.I), self._abbr),
            (re.compile(rf"(?<![\w+])(\+358{SP}?|0)(\d{{1,3}})((?:[ -]\d{{2,7}}){{1,3}})(?![\d,]|\.\d)"),
             self._phone),
            (re.compile(r"(?<![\d.,])(\d{1,2})\.(\d{1,2})\.(\d{4})?(?![\d:]|\.\d)"), self._date),
            # Windowsin pitkä päiväys (NVDA+F12 kahdesti): "25. lokakuuta 2026".
            (re.compile(rf"{NB}(\d{{1,2}})\.{SP}+({'|'.join(MONTHS)})(?:{SP}+(\d{{4}}))?(?![\w])", re.I),
             self._date_named),
            (re.compile(r"\b(kello)\s+(\d{1,2})(?:[.:](\d{2}))?"
                        r"(?:\s?[–—-]\s?(\d{1,2})(?:[.:](\d{2}))?)?(?![\d:]|[.,]\d)", re.I),
             self._time),
            (re.compile(rf"{NB}({MINUS})?({INT})(?:,(\d{{2}}|-))?{SP}?€(?![\w:])"),
             self._money),
            (re.compile(rf"{NB}(\d+)(?:{SP}?[–—]{SP}?|-)(\d+)(?![\d,:]|\.\d)"),
             self._range),
            (re.compile(rf"{NB}({MINUS})?({INT})(?:,(\d+))?{SP}?({unit_alt})"
                        rf"(?::([{LOWER}]+))?(?![\w%°])"), self._unit),
            (re.compile(rf"{NB}({INT})-([{LOWER}]+)"), self._hyphen),
            (re.compile(rf"{NB}({INT}):([{LOWER}]+)(?![\w])"), self._colon),
            (re.compile(rf"{NB}(\d+)\.(?={SP}+[{LOWER}])"), self._ordinal),
            (re.compile(rf"{NB}({MINUS})?(\d+),(\d+)(?![\d,]|\.\d)"), self._decimal),
            (re.compile(rf"{NB}({MINUS})?({INT})(?![\w]|[,.:]\d|:)"), self._integer),
        ]

    # ---------- lukusanojen kokoaminen taulukosta ----------

    def card(self, n: int, case: str = "Sg+Nom") -> str | None:
        """Perusluku sanoina. Ryhmät (miljoonat, tuhannet, sadat, loput) erotetaan
        välilyönnillä, jotta syntetisaattori saa painolliset rajat."""
        if n == 0:
            return self.words["nolla"].get(case)
        if n >= 10**12:
            return None
        words: list[str] = []
        for scale in (10**9, 10**6, 1000):
            q, n = divmod(n, scale)
            if q:
                atom = self._atom(scale, case)
                if q == 1:
                    group = [atom]
                else:
                    group = self._below1000(q, case)
                    group[-1] += self._mult(scale, case)
                words += group
        if n:
            words += self._below1000(n, case)
        if any(w is None for w in words) or "None" in "".join(words):
            return None
        return " ".join(words)

    def _atom(self, n: int, case: str) -> str | None:
        return self.card_atoms[n].get(case)

    def _mult(self, scale: int, case: str) -> str:
        # kaksikymmentä, kaksisataa, kaksituhatta: kerrottava osa on perusmuodossa
        # partitiivissa, muissa sijoissa samassa sijassa (kahdellekymmenelle).
        form = self._atom(scale, "Sg+Par" if case == "Sg+Nom" else case)
        sep = " " if scale >= 10**6 else ""
        return sep + str(form)

    def _below1000(self, n: int, case: str) -> list[str]:
        out = []
        h, n = divmod(n, 100)
        if h:
            out.append(str(self._atom(100, case)) if h == 1
                       else str(self._atom(h, case)) + self._mult(100, case))
        if n:
            out.append(self._below100(n, case))
        return out

    def _below100(self, n: int, case: str) -> str:
        if n <= 10:
            return str(self._atom(n, case))
        if n < 20:
            return str(self._atom(n - 10, case)) + "toista"
        t, u = divmod(n, 10)
        s = str(self._atom(t, case)) + self._mult(10, case)
        return s + (str(self._atom(u, case)) if u else "")

    def ordinal(self, n: int, form: str = "Sg+Nom") -> str | None:
        """Järjestysluku sanoina, kaikki osat taipuvat (kahdennellekymmenennelleviidennelle)."""
        if not 0 < n < 10**6:
            return None
        parts: list[str | None] = []
        q, r = divmod(n, 1000)
        if q:
            if q == 1:
                parts.append(self.ord_atoms[1000].get(form))
            else:
                parts.append(self._ord_below1000(q, form, final=False)
                             + str(self.ord_atoms[1000].get(form)))
        if r:
            parts.append(self._ord_below1000(r, form, final=True))
        if any(p is None or "None" in p for p in parts):
            return None
        return " ".join(parts)

    def _ord_unit(self, u: int, form: str, final: bool) -> str:
        if not final and u in (1, 2):
            u = "yhdes" if u == 1 else "kahdes"
        return str(self.ord_atoms[u].get(form))

    def _ord_below1000(self, n: int, form: str, final: bool) -> str:
        s = ""
        h, n = divmod(n, 100)
        if h:
            s += ("" if h == 1 else self._ord_unit(h, form, False)) + str(self.ord_atoms[100].get(form))
        if n:
            if n <= 10:
                s += self._ord_unit(n, form, final) if n < 10 else str(self.ord_atoms[10].get(form))
            elif n < 20:
                u = n - 10
                s += str(self.ord_atoms["yhdes" if u == 1 else "kahdes" if u == 2 else u].get(form)) + "toista"
            else:
                t, u = divmod(n, 10)
                s += self._ord_unit(t, form, False) + str(self.ord_atoms[10].get(form))
                if u:
                    s += self._ord_unit(u, form, final)
        return s

    def decimal(self, whole: int, frac: str) -> str | None:
        w = self.card(whole)
        if w is None:
            return None
        if len(frac) <= 2 and not frac.startswith("0"):
            f = self.card(int(frac))
        else:
            f = " ".join(DIGITS[int(c)] for c in frac)
        return f"{w} pilkku {f}"

    # ---------- yhteys: mitä luvun perässä on ----------

    def context(self, text: str, end: int):
        """Palauttaa ("loppu", None), ("tuntematon", None), ("ei-nomini", None)
        tai ("nomini", {(luku, sija), ...}) seuraavasta sanasta."""
        m = re.match(rf"{SP}*([{LETTER}]+)?", text[end:])
        word = m.group(1)
        if word is None:
            rest = text[end:].lstrip("   ")
            if not rest or rest[0] in ",;!?)]}\"'”/…":
                return ("loppu", None)
            # "3." tekstin lopussa on luku; "3. Hän" ja "3. kerros" voivat olla järjestyslukuja.
            if rest[0] == "." and not rest[1:].strip():
                return ("loppu", None)
            return ("tuntematon", None)
        if word[0].isupper():
            return ("tuntematon", None)
        mask = self.sijat.get(word)
        if mask is None:
            return ("tuntematon", None)
        readings = {(NUMS[b // 14], CASES[b % 14]) for b in range(28) if mask >> b & 1}
        return ("nomini", readings) if readings else ("ei-nomini", None)

    @staticmethod
    def negated(text: str, start: int) -> bool:
        clause = re.split(r"[.!?;:,]", text[:start])[-1]
        return any(w.lower() in NEGATION for w in re.findall(rf"[{LETTER}]+", clause))

    def partitive_verb(self, text: str, start: int, end: int) -> bool:
        """Onko luvun lauseessa (ennen tai jälkeen: 3 koiraa rakastava) verbi, jonka
        objekti on aina partitiivissa. Luvun perässä oleva sana itse ohitetaan, koska
        laskettava sana voi olla verbin kanssa samannäköinen (3 seuraa, 3 uhkaa)."""
        after = re.sub(rf"^{SP}*[{LETTER}]+", "", text[end:])
        clause = re.split(r"[.!?;:,]", text[:start])[-1] + " " + re.split(r"[.!?;:,]", after)[0]
        for w in re.findall(rf"[{LETTER}]+", clause.lower()):
            if w in self.partitive_verbs or CLITIC.sub("", w) in self.partitive_verbs:
                return True
        return False

    def cardinal_case(self, n: int | None, text: str, start: int, end: int) -> str | None:
        """Perusluvun sija seuraavan sanan perusteella, tai None jos epävarma.
        n=None tarkoittaa desimaalilukua (vain perusmuoto sallitaan)."""
        kind, readings = self.context(text, end)
        if kind in ("loppu", "ei-nomini"):
            return "Sg+Nom"
        if kind != "nomini":
            return None
        cases = set()
        for num, case in readings:
            if case == "Par" and num == "Sg":
                # Partitiivisubstantiivin edellä luku on perusmuodossa (1 347 osallistujaa),
                # paitsi kiellon jälkeen (en nähnyt kolmea lintua) ja partitiiviverbin
                # objektina (rakastan kolmea koiraa): silloin ei arvata. Kesto ja kerta
                # (odotin 3 tuntia) ovat ajanilmauksia, joissa perusmuoto pätee verbistä riippumatta.
                word = re.match(rf"{SP}*([{LETTER}]+)", text[end:]).group(1)
                governed = (self.partitive_verb(text, start, end)
                            and word.lower() not in self.measure_words)
                cases.add(None if n == 1 or self.negated(text, start) or governed else "Sg+Nom")
            elif case == "Nom":
                cases.add("Sg+Nom")
            elif num == "Sg":
                cases.add(f"Sg+{case}")
            else:
                cases.add(None)       # monikko: kolmet housut, ei tueta
        if len(cases) != 1:
            return None
        case = cases.pop()
        if n is None and case != "Sg+Nom":
            return None
        return case

    # ---------- passit ----------

    @staticmethod
    def _int(s: str) -> int:
        return int(re.sub(r"\D", "", s))

    @staticmethod
    def _sign(s: str | None) -> str:
        return "miinus " if s else ""

    def _acronym(self, m: re.Match, text: str) -> str | None:
        raw, suffix = m.group(1), m.group(2) or ""
        if raw in ACRONYM_WORD:
            return ACRONYM_WORD[raw] + suffix
        return " ".join(LETTER_NAMES[c] for c in raw) + suffix

    def _abbr(self, m: re.Match, text: str) -> str | None:
        raw = m.group(1)
        word, cond = ABBR[raw.lower()]
        after = text[m.end():]
        if cond == "num" and not re.match(rf"{SP}*{MINUS}?\d", after):
            return None
        if cond == "vuosi" and not re.match(rf"{SP}*\d{{4}}(?!\d)", after):
            return None
        if raw[0].isupper():
            word = word[0].upper() + word[1:]
        if raw.lower() in SENTENCE_FINAL_ABBR and (not after.strip() or
                                                   re.match(r"\s+[A-ZÅÄÖ]", after)):
            word += "."
        return word

    def _phone(self, m: re.Match, text: str) -> str | None:
        rest = re.split(r"[ -]", m.group(3).strip(" -"))
        if m.group(1).startswith("+358"):
            groups = ["+358", m.group(2)] + rest
        else:
            groups = [m.group(1) + m.group(2)] + rest
        digits = "".join(groups).replace("+", "")
        if not 7 <= len(digits) <= 13:
            return None

        def say(g: str) -> str:
            return ("plus " if g.startswith("+") else "") + " ".join(DIGITS[int(c)] for c in g.lstrip("+"))
        # Ryhmien väliin pilkku: syntetisaattori pitää tauon kuten numeroa sanellessa.
        return ", ".join(say(g) for g in groups)

    def _date(self, m: re.Match, text: str) -> str | None:
        d, mo, y = int(m.group(1)), int(m.group(2)), m.group(3)
        if not (1 <= d <= 31 and 1 <= mo <= 12):
            return None
        out = f"{self.ordinal(d)} {MONTHS[mo - 1]}"
        if y:
            out += " " + self.card(int(y))
        else:
            # "25.10." : toinen piste on päiväyksen osa, mutta jos virke päättyy, se jää.
            if not text[m.end():].strip():
                out += "."
        return out

    def _date_named(self, m: re.Match, text: str) -> str | None:
        # Päivä on perusmuodossa kuten numeropäiväyksessä (kahdeskymmenesviides lokakuuta).
        # Ilman tätä järjestyslukusääntö taivuttaisi sen kuukauden partitiivin mukaan.
        d, month, y = int(m.group(1)), m.group(2), m.group(3)
        if not 1 <= d <= 31:
            return None
        out = f"{self.ordinal(d)} {month}"
        if y:
            out += " " + self.card(int(y))
        return out

    def _time(self, m: re.Match, text: str) -> str | None:
        word, h1, m1, h2, m2 = m.groups()
        def clock(h, mi, case):
            h = int(h)
            if h > 24 or (mi and int(mi) > 59):
                return None
            s = self.card(h, case)
            if mi and int(mi):
                if mi.startswith("0"):
                    if case != "Sg+Nom":
                        return None
                    s += " nolla " + DIGITS[int(mi[1])]
                else:
                    s += " " + self.card(int(mi), case)
            return s
        if h2 is None:
            t = clock(h1, m1, "Sg+Nom")
            return t and f"{word} {t}"
        a, b = clock(h1, m1, "Sg+Ela"), clock(h2, m2, "Sg+Ill")
        return a and b and f"{word} {a} {b}"

    def _money(self, m: re.Match, text: str) -> str | None:
        sign, whole, cents = m.groups()
        e = self._int(whole)
        c = 0 if cents in (None, "-") else int(cents)
        parts = []
        if e or not c:
            w = self.card(e)
            if w is None:
                return None    # yli biljoona: ilman tätä tulos olisi "None euroa"
            parts.append("yksi euro" if e == 1 else f"{w} euroa")
        if c:
            parts.append("yksi sentti" if c == 1 else f"{self.card(c)} senttiä")
        return self._sign(sign) + " ".join(parts)

    def _range(self, m: re.Match, text: str) -> str | None:
        a, b = int(m.group(1)), int(m.group(2))
        if not a < b:
            return None        # 3-2 on tulos eikä väli
        after = text[m.end():]
        unit = re.match(rf"{SP}?({'|'.join(re.escape(u) for u in sorted(self.units, key=len, reverse=True))})(?![\w%°:])", after)
        tail, consumed = "", 0
        if unit:
            tail = " " + self.words[self.units[unit.group(1)]]["Sg+Par"]
            consumed = unit.end()
        elif self.cardinal_case(None, text, m.start(), m.end()) != "Sg+Nom":
            return None
        x, y = self.card(a, "Sg+Ela"), self.card(b, "Sg+Ill")
        if not (x and y):
            return None
        return (f"{x} {y}{tail}", consumed)

    def _unit(self, m: re.Match, text: str) -> str | None:
        sign, whole, frac, unit, suffix = m.groups()
        lemma = self.units[unit]
        par = self.words[lemma]
        if suffix:
            case = self._suffix_case(suffix, par)
            if case is None:
                return None
        else:
            case = "Sg+Nom"
        n = self._int(whole)
        if case in ("Sg+Nom", "Sg+Par"):
            num = self.decimal(n, frac) if frac else self.card(n)
            noun = par["Sg+Nom"] if n == 1 and not frac else par["Sg+Par"]
        else:
            if frac:
                return None    # 3,5 metrin: osien taivutusta ei ole todennettu
            num, noun = self.card(n, case), par.get(case)
        if not (num and noun):
            return None
        return f"{self._sign(sign)}{num} {noun}"

    @staticmethod
    def _suffix_case(suffix: str, paradigm: dict) -> str | None:
        for pattern, case in SUFFIX_CASE:
            form = paradigm.get(f"Sg+{case}")
            if re.fullmatch(pattern, suffix) and form and form.endswith(suffix):
                return f"Sg+{case}"
        return None

    def _hyphen(self, m: re.Match, text: str) -> str | None:
        # 1990-luvulla, 5-vuotias: yhdyssanan alkuosa on aina perusmuodossa.
        # Viiva korvataan välilyönnillä, koska syntetisaattori lukee sen "viivana".
        num = self.card(self._int(m.group(1)))
        return num and f"{num} {m.group(2)}"

    def _colon(self, m: re.Match, text: str) -> str | None:
        n, suffix = self._int(m.group(1)), m.group(2)
        forms = {c: self.card(n, f"Sg+{c}") for c in CASES[:11]}
        case = self._suffix_case(suffix, {f"Sg+{c}": f for c, f in forms.items() if f})
        if case:
            return self.card(n, case)
        # Järjestysluku: 5:s, 5:nnen, 5:nteen, 2:sen. Lyhyt pääte (5:a) ei riitä
        # erottamaan sijaa, joten vaaditaan s, n-alku tai vähintään kolme kirjainta.
        if suffix == "s" or suffix.startswith("n") or len(suffix) >= 3:
            hits = {self.ordinal(n, f) for f in self.ord_atoms[1]
                    if f.startswith("Sg") and (self.ordinal(n, f) or "").endswith(suffix)}
            hits.discard(None)
            if len(hits) == 1:
                return hits.pop()
        return None

    def _ordinal(self, m: re.Match, text: str) -> str | None:
        n = int(m.group(1))
        # Kuukauden edellä luku on päiväys, jonka _date_named on jo ratkaissut tai hylännyt.
        if re.match(rf"{SP}+(?:{'|'.join(MONTHS)})(?![\w])", text[m.end():], re.I):
            return None
        kind, readings = self.context(text, m.end())
        if kind != "nomini":
            return None
        forms = {self.ordinal(n, f"{num}+{case}") for num, case in readings}
        if len(forms) != 1:
            return None
        return forms.pop()

    def _decimal(self, m: re.Match, text: str) -> str | None:
        sign, whole, frac = m.groups()
        if self.cardinal_case(None, text, m.start(), m.end()) != "Sg+Nom":
            return None
        d = self.decimal(int(whole), frac)
        return d and self._sign(sign) + d

    def _integer(self, m: re.Match, text: str) -> str | None:
        sign, raw = m.groups()
        digits = re.sub(r"\D", "", raw)
        if len(digits) > 1 and digits.startswith("0"):
            return None
        n = int(digits)
        case = self.cardinal_case(n, text, m.start(), m.end())
        if case is None:
            return None
        word = self.card(n, case)
        return word and self._sign(sign) + word

    # ---------- ajo ----------

    def avaa(self, text: str) -> str:
        for regex, fn in self.passes:
            out, pos = [], 0
            for m in regex.finditer(text):
                if m.start() < pos:
                    continue
                res = fn(m, text)
                if res is None:
                    continue
                consumed = 0
                if isinstance(res, tuple):
                    res, consumed = res
                out.append(text[pos:m.start()])
                out.append(res)
                pos = m.end() + consumed
            out.append(text[pos:])
            text = "".join(out)
        return text


def main() -> None:
    a = Avaaja()
    for line in (sys.argv[1:] or sys.stdin.read().splitlines()):
        print(a.avaa(line))


if __name__ == "__main__":
    main()
