"""NVDA-kytkennän testit ilman NVDA:ta. Ajo: python -X utf8 tests/test_nvda.py

NVDA:n moduulit korvataan pienillä tyngillä, jotta suodatin, kielen käsittely ja
päälle/pois-komento voidaan ajaa tavallisessa Pythonissa. Lisäksi tarkistetaan, että
käyttöohje nimeää komennon, luokan ja ilmoitukset samoin kuin koodi.
"""
import re
import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


class LangChangeCommand:
    def __init__(self, lang):
        self.lang = lang


class CharacterModeCommand:
    def __init__(self, state):
        self.state = state


class Filter:
    def __init__(self):
        self.handlers = []

    def register(self, h):
        self.handlers.append(h)

    def unregister(self, h):
        self.handlers.remove(h)

    def apply(self, value):
        for h in self.handlers:
            value = h(value)
        return value


def install_stubs(language="fi_FI", auto_switch=True):
    messages = []
    mods = {
        "config": types.SimpleNamespace(conf={"speech": {"autoLanguageSwitching": auto_switch}}),
        "globalPluginHandler": types.SimpleNamespace(GlobalPlugin=type("GlobalPlugin", (), {
            "__init__": lambda self: None, "terminate": lambda self: None})),
        "ui": types.SimpleNamespace(message=messages.append),
        "logHandler": types.SimpleNamespace(log=types.SimpleNamespace(exception=lambda *a: None)),
        "scriptHandler": types.SimpleNamespace(script=lambda **kw: (lambda f: setattr(f, "kw", kw) or f)),
        "speech": types.SimpleNamespace(extensions=types.SimpleNamespace(filter_speechSequence=Filter())),
        "speech.commands": types.SimpleNamespace(LangChangeCommand=LangChangeCommand,
                                                 CharacterModeCommand=CharacterModeCommand),
        "speech.speech": types.SimpleNamespace(getCurrentLanguage=lambda: language),
    }
    for name, mod in mods.items():
        sys.modules[name] = mod
    sys.modules["speech.extensions"] = mods["speech"].extensions
    return mods, messages


def load_plugin():
    sys.path.insert(0, str(ROOT / "addon" / "globalPlugins"))
    for m in [m for m in sys.modules if m == "lausu" or m.startswith("lausu.")]:
        del sys.modules[m]
    import lausu
    return lausu


def main() -> int:
    fails = []

    def check(name, got, exp):
        if got != exp:
            fails.append(f"{name}\n  odotettu: {exp!r}\n  saatiin:  {got!r}")

    mods, messages = install_stubs()
    lausu = load_plugin()
    plugin = lausu.GlobalPlugin()
    flt = mods["speech"].extensions.filter_speechSequence
    check("rekisteröinti", flt.handlers, [plugin._filter])

    en = LangChangeCommand("en_GB")
    fi = LangChangeCommand(None)
    seq = ["Juna saapuu 5:een mennessä.", en, "Arrives by 5:00.", fi, "Soitin 2:lle ihmiselle."]
    check("kieli vaihtuu", flt.apply(list(seq)),
          ["Juna saapuu viiteen mennessä.", en, "Arrives by 5:00.", fi, "Soitin kahdelle ihmiselle."])

    cm_on, cm_off = CharacterModeCommand(True), CharacterModeCommand(False)
    check("tavaus ennallaan", flt.apply([cm_on, "5:een", cm_off, "5:een"]), [cm_on, "5:een", cm_off, "viiteen"])

    # Toggle: komento sammuttaa ja sytyttää, ja ilmoitukset vastaavat ohjetta.
    plugin.script_toggle(None)
    check("pois", flt.apply(["5:een"]), ["5:een"])
    plugin.script_toggle(None)
    check("päälle", flt.apply(["5:een"]), ["viiteen"])
    check("ilmoitukset", messages, ["Lausu pois", "Lausu päällä"])

    plugin.terminate()
    check("poisto", flt.handlers, [])

    # Automaattinen kielenvaihto pois: ääni ei vaihdu, joten merkitty teksti luetaan suomeksi.
    mods, _ = install_stubs(auto_switch=False)
    plugin = load_plugin().GlobalPlugin()
    flt = mods["speech"].extensions.filter_speechSequence
    check("kielenvaihto pois", flt.apply([en, "5:een"]), [en, "viiteen"])

    # Englantilainen ääni: ei avausta.
    mods, _ = install_stubs(language="en_US")
    load_plugin().GlobalPlugin()
    flt = mods["speech"].extensions.filter_speechSequence
    check("englantilainen ääni", flt.apply(["5:een"]), ["5:een"])

    # Avauksen virhe ei saa katkaista puhetta.
    mods, _ = install_stubs()
    plugin = load_plugin().GlobalPlugin()
    plugin.avaaja.avaa = lambda t: 1 / 0
    check("virhe", mods["speech"].extensions.filter_speechSequence.apply(["5:een"]), ["5:een"])

    # Käyttöohje nimeää komennon, luokan ja ilmoitukset koodin merkkijonoin.
    doc = re.sub(r"<[^>]+>|\s+", " ", (ROOT / "addon" / "doc" / "fi" / "readme.html").read_text(encoding="utf-8"))
    kw = lausu.GlobalPlugin.script_toggle.kw
    for s in (kw["description"], kw["category"], "Lausu päällä", "Lausu pois"):
        if s not in doc:
            fails.append(f"käyttöohjeesta puuttuu {s!r}")

    for f in fails:
        print("VIRHE", f)
    print(f"NVDA-kytkentä: {'kaikki läpi' if not fails else f'{len(fails)} virhettä'}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
