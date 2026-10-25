"""Vertailukorpus Python- ja Kotlin-toteutukselle.

Tuottaa satunnaisia lauseita, joissa luku- ja lyhennekuvioita on eri sanojen ja sijamuotojen
vieressä, ja kirjoittaa Python-kerroksen tuloksen kunkin perään. Kotlin-testi
(android/kerros, VertailuTest) ajaa samat syötteet ja vaatii saman tuloksen. Testitapaukset
(tapaukset.txt) määrittelevät oikean tuloksen, tämä korpus vain sen, että toteutukset ovat samat.

Ajo:  python -X utf8 tests/vertailukorpus.py [määrä]   ->  build/tyo/vertailu.tsv (ei versioida)
Sitten:  android/gradlew -p android :kerros:test -Plausu.vertailu=../build/tyo/vertailu.tsv
"""
import random
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "addon" / "globalPlugins" / "lausu"))
from avaa import Avaaja  # noqa: E402

WORDFORMS = ROOT.parent / "SanaMix" / "data" / "wordforms.txt"
OUT = ROOT / "build" / "tyo" / "vertailu.tsv"

LUVUT = ["0", "1", "2", "3", "5", "7", "10", "11", "12", "19", "20", "21", "25", "99", "100",
         "101", "347", "999", "1000", "1001", "1990", "2026", "1 347", "12 000", "2 500 000",
         "1 000 000", "007", "1000000000000", "123456789012345678901"]
KUVIOT = [
    "{n}", "{n}", "{n}", "-{n}", "−{n}", "{n},5", "{n},05", "{n},123", "{n}.", "{n}:{suf}",
    "{n}-{suf}", "{n}–{m}", "{n}-{m}", "{n} €", "{n},90 €", "{n},- €", "{n} km", "{n} km:{suf}",
    "{n} %", "{n}%:{suf}", "{n} °C", "klo {h}", "klo {h}.{mi}", "klo {h}–{h2}", "kello {h}:{mi}",
    "{d}.{mo}.", "{d}.{mo}.{y}", "{d}. lokakuuta", "{d}. lokakuuta {y}", "040 123 4567",
    "+358 40 123 4567", "n. {n}", "s. {n}", "v. {y}", "esim.", "Esim.", "jne.", "YLE:{suf}",
    "EU:{suf}", "USA", "NATO", "{n} / {m}", "{n}/{m}",
    "{n} €/{per}", "{n},50 €/{per}", "{n} km/{per}", "{n} m/{per}", "{n} vrk", "{n} kk:{suf}",
    "{d} heinäkuuta", "{d} lokakuuta {y}", "{n} heinäkuuta",
    "{n}/{m}/{n}", "{n},5/{m}", "{n}/{m}:{suf}", "Heitto {n}/{m}",
    "THL:{suf}", "VR:{suf}", "PDF:{suf}", "HSL:{suf}", "SAK:{suf}", "USB:{suf}", "THL",
]
PER = ["kk", "vrk", "h", "min", "s", "kpl", "snt", "kk:n", "hh"]
SUFFIKSIT = ["n", "a", "ä", "ta", "ttä", "een", "ään", "aan", "seen", "lle", "lla", "ssa", "sta",
             "ksi", "na", "s", "nnen", "nteen", "sen", "luvulla", "vuotias", "x",
             "ää", "llä", "nä", "ssä", "ltä", "hen"]
ALUT = ["", "", "Hän näki", "En nähnyt", "Rakastan", "Odotin", "Ostin", "Tänään on", "Sivu",
        "Autoimme", "Kävin", "Juna saapuu", "Hinta oli", "Se maksoi"]
LOPUT = ["", ".", ",", "?", " ja", " mennessä.", " Seuraava", " (noin)"]


def sanat(n: int, rnd: random.Random) -> list[str]:
    """Otos sanamuotoja Itun listasta, painottuen nomineihin."""
    rivit = WORDFORMS.read_text(encoding="utf-8").splitlines()
    otos = rnd.sample(rivit, n * 3)
    out = [r.split("\t")[0] for r in otos if "#N+" in r or "#A+" in r][:n]
    return out + ["kerros", "kerrokseen", "omenaa", "koiraa", "tuntia", "kertaa", "ihmiselle",
                  "osallistujaa", "luokille", "housuissa", "xyzzyä", "Kelan"]


def main() -> None:
    maara = int(sys.argv[1]) if len(sys.argv) > 1 else 20000
    rnd = random.Random(20261025)
    sanalista = sanat(3000, rnd)
    a = Avaaja()
    syotteet = [r.split("\t", 1)[1] for r in (HERE / "lauseet.txt").read_text(encoding="utf-8").splitlines() if "\t" in r]
    while len(syotteet) < maara:
        kuvio = rnd.choice(KUVIOT).format(
            n=rnd.choice(LUVUT), m=rnd.choice(LUVUT), suf=rnd.choice(SUFFIKSIT), per=rnd.choice(PER),
            h=rnd.randint(0, 30), h2=rnd.randint(0, 30), mi=f"{rnd.randint(0, 70):02d}",
            d=rnd.randint(0, 35), mo=rnd.randint(0, 14), y=rnd.choice(["1990", "2026", "0000", "12345"]))
        osat = [rnd.choice(ALUT), kuvio]
        if rnd.random() < 0.8:
            osat.append(" ".join(rnd.choice(sanalista) for _ in range(rnd.randint(1, 2))))
        syotteet.append(" ".join(o for o in osat if o).replace(" ", rnd.choice(["  ", " ", " ", " "]), 1)
                        + rnd.choice(LOPUT))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8", newline="\n") as f:
        for s in syotteet:
            assert "\t" not in s and "\n" not in s
            f.write(f"{s}\t{a.avaa(s)}\n")
    muuttui = sum(1 for s in syotteet if a.avaa(s) != s)
    print(f"{len(syotteet)} syötettä, joista {muuttui} muuttui -> {OUT}")


if __name__ == "__main__":
    main()
