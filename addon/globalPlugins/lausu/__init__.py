# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Tommi Haanranta
"""Lausu-lisäosan NVDA-kytkentä.

Rekisteröi suodattimen NVDA:n puheketjuun (speech.extensions.filter_speechSequence,
NVDA 2024.2:sta alkaen). Suodatin ajetaan ennen NVDA:n puhesanakirjoja ja symbolien
käsittelyä, joten se näkee tekstin numeroineen. Avaus tehdään vain tekstille, jonka
syntetisaattori lukee suomeksi; kirjaintila (tavaus) jätetään rauhaan.
"""
import config
import globalPluginHandler
import ui
from logHandler import log
from scriptHandler import script
from speech import extensions
from speech.commands import CharacterModeCommand, LangChangeCommand
from speech.speech import getCurrentLanguage

from .avaa import Avaaja


def _is_finnish(lang: str | None) -> bool:
    return bool(lang) and lang.replace("-", "_").split("_")[0].lower() == "fi"


def suodata(sequence: list, avaaja: Avaaja, default_language: str, follow_lang_changes: bool) -> list:
    """Avaa sekvenssin suomenkieliset tekstipalat. Muut alkiot kulkevat sellaisinaan.

    Kielimerkintää noudatetaan vain, jos NVDA:n automaattinen kielenvaihto on päällä:
    muuten ääni ei vaihdu, ja merkitty teksti luetaan oletuskielellä.
    """
    lang = default_language
    char_mode = False
    out = []
    for item in sequence:
        if isinstance(item, LangChangeCommand):
            if follow_lang_changes:
                lang = item.lang or default_language
        elif isinstance(item, CharacterModeCommand):
            char_mode = item.state
        elif isinstance(item, str) and not char_mode and _is_finnish(lang):
            try:
                item = avaaja.avaa(item)
            except Exception:
                # Virhe ei saa koskaan katkaista puhetta: alkuperäinen teksti menee läpi.
                log.exception("Lausu: tekstin avaus epäonnistui")
        out.append(item)
    return out


class GlobalPlugin(globalPluginHandler.GlobalPlugin):
    def __init__(self):
        super().__init__()
        self.enabled = True
        self.avaaja = Avaaja()
        extensions.filter_speechSequence.register(self._filter)

    def terminate(self):
        extensions.filter_speechSequence.unregister(self._filter)
        super().terminate()

    def _filter(self, value: list) -> list:
        if not self.enabled:
            return value
        return suodata(value, self.avaaja, getCurrentLanguage(),
                       config.conf["speech"]["autoLanguageSwitching"])

    @script(
        description="Kytkee lukujen ja lyhenteiden avauksen päälle tai pois",
        category="Lausu",
    )
    def script_toggle(self, gesture):
        self.enabled = not self.enabled
        ui.message("Lausu päällä" if self.enabled else "Lausu pois")
