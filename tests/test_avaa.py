"""Avauskerroksen testit. Ajo: python -X utf8 tests/test_avaa.py

Tapaukset ovat tiedostossa tests/tapaukset.txt, jonka myös Kotlin-toteutus
(android/kerros) ajaa. Odotettu == syöte tarkoittaa, että kerroksen pitää jättää
kohta ennalleen (ei arvailua).
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "addon" / "globalPlugins" / "lausu"))
from avaa import Avaaja  # noqa: E402

TAPAUKSET = HERE / "tapaukset.txt"


def lue_tapaukset(polku: Path = TAPAUKSET) -> list[tuple[str, str, str]]:
    """Palauttaa (tunnus, syöte, odotettu). Muoto on kuvattu tiedoston alussa."""
    out = []
    for rivi in polku.read_text(encoding="utf-8").splitlines():
        if not rivi.strip() or rivi.startswith("#"):
            continue
        kentat = [k for k in rivi.split("\t") if not k.startswith("#")]
        tunnus = kentat.pop(0)[1:] if kentat[0].startswith("@") else ""
        syote, odotettu = kentat
        out.append((tunnus, syote, odotettu))
    return out


def main() -> int:
    a = Avaaja()
    fails = 0
    total = 0
    for name, inp, exp in lue_tapaukset():
        total += 1
        got = a.avaa(inp)
        if got != exp:
            fails += 1
            print(f"VIRHE {name} {inp!r}\n  odotettu: {exp!r}\n  saatiin:  {got!r}")
    print(f"{total - fails}/{total} läpi")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
