# Lausu

NVDA-lisäosa, joka kirjoittaa suomenkielisen tekstin luvut, lyhenteet ja vakiokuviot auki
oikeassa taivutusmuodossa ennen puhesyntetisaattoria.

| Teksti | Lausu kirjoittaa |
|---|---|
| Juna saapuu 5:een mennessä. | Juna saapuu viiteen mennessä. |
| Nouse 2. kerrokseen. | Nouse toiseen kerrokseen. |
| n. 5 km:n matka | noin viiden kilometrin matka |
| Paikalla on 1 347 osallistujaa. | Paikalla on tuhat kolmesataa neljäkymmentäseitsemän osallistujaa. |
| 12,90 € | kaksitoista euroa yhdeksänkymmentä senttiä |

## Miksi

Suomenkieliset puheäänet (Mikropuhe, Microsoft Heidi, eSpeak NG, Piper) lukevat numerot ja
lyhenteet usein väärin, koska luvun muoto riippuu taivutuksesta. Vika on syntetisaattoria
edeltävässä tekstin avauksessa eikä äänessä. Lausu tekee avauksen erillisenä kerroksena, joten
se toimii minkä tahansa suomenkielisen äänen kanssa.

Kokeilussa 25.10.2026 kuulija arvioi kolmetoista ongelmakohtaa ennen ja jälkeen. Mikropuheen
Saga ja Piperin Harri sanoivat ennen kumpikin neljä oikein, Lausun kanssa kaikki kolmetoista.
Raportti: [docs/KOKEILU-2026-10-25.md](docs/KOKEILU-2026-10-25.md).

## Periaatteet

- **Ajon aikana vain hakuja.** Lukusanojen ja sanamuotojen taivutustiedot tuotetaan
  käännösvaiheessa omorfi-morfologialla. Lause käsitellään noin 23 µs:ssa, kun syntetisaattori
  käyttää samaan 3–20 ms.
- **Ei arvailua.** Jos sääntö ei tunnista tapausta varmasti, teksti jää ennalleen.
- **Pieni.** Lisäosa on alle megatavun. Sijatieto 3,4 miljoonalle sanamuodolle mahtuu 1,8 Mt:n
  sanapuuhun (DAWG).

## Tila

Kokeiluversio 0.1.0. Kerros ja NVDA-kytkentä on testattu tavallisessa Pythonissa NVDA:n
moduulit tyngiksi korvaten. Lisäosa on ajettu NVDA 2026.2:ssa muistiota vasten
(docs/KOKEILU-2026-10-25.md › NVDA-ajo): se latautui, luvut avautuivat ja päälle/pois-komento
toimi. Selaintila on ajettu kerran (Superjatsi Chromessa). Kytkentä käyttää
NVDA:n puhesekvenssin suodatinta (`speech.extensions.filter_speechSequence`), joka on
NVDA 2024.2:sta alkaen.

## Rakenne

```
addon/                     lisäosan sisältö (manifest.ini, käyttöohje doc/fi/)
  globalPlugins/lausu/     __init__.py (NVDA-kytkentä), avaa.py (kerros), sanapuu.py, data/
build/                     käännösvaihe ja pakkaus
tests/                     tapaukset.txt (kerroksen määrittely), test_avaa.py (kerros),
                           test_nvda.py (kytkentä), mittaa.py (nopeus), vertailukorpus.py
android/                   Android-versio: kerros/ (Kotlin) ja moottori/ (puhemoottori TalkBackille)
docs/                      kokeilun raportti, lisenssiselvitys ja Android-suunnitelma (ANDROID.md)
```

Rakennus (Python 3.12, käännösvaiheeseen tarvitaan uralicNLP ja sen fin-malli):

```
python -X utf8 build/build_tables.py      # lukusanat ja sijataulukko
python -X utf8 build/rakenna_sanapuu.py   # sijataulukko sanapuuksi, todennus
python -X utf8 tests/test_avaa.py
python -X utf8 tests/test_nvda.py
python -X utf8 build/build_addon.py       # dist/lausu-<versio>.nvda-addon
```

Sijataulukon lähde on toistaiseksi [Itun](https://github.com/tommihaa/Itu) generoima
sanamuotolista (`SanaMix/data/wordforms.txt` viereisessä kansiossa).

## Lisenssi ja lähteet

**GNU GPL v3 tai myöhempi**, ks. [COPYING.txt](COPYING.txt). Copyright (C) 2026 Tommi Haanranta.

- Taivutusmuodot on tuotettu [omorfi](https://github.com/flammie/omorfi)-morfologialla
  (GNU GPLv3) [uralicNLP](https://github.com/mikahama/uralicNLP):n kautta. Omorfin tekijöiden
  kanta (27.8.2026) on, että generoidun sanaston käyttö on GPL:n hengen mukaista ja että sitä
  hyödyntävät sovellukset ovat samalla lisenssillä. Siksi Lausu on GPL.
- Sanaston lähde: [Nykysuomen sanalista](https://www.kotus.fi/sanakirjat/kielitoimiston-sanakirja/nykysuomen-sana-aineistot/nykysuomen-sanalista/),
  Kotimaisten kielten keskus, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
  Lista on suodatettu ja sen sanoista on generoitu taivutusmuodot. Sama nimeäminen on
  lisäosan käyttöohjeessa.

Lausu on kehitetty yhdessä Claude Coden kanssa.
