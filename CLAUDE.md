# Lausu: projektin säännöt

NVDA-lisäosa, joka avaa suomen tekstin luvut ja lyhenteet taivutettuina ennen syntetisaattoria.
Kuvaus ja rakenne: README.md. Kokeilun mittaukset ja lisenssiselvitys: docs/KOKEILU-2026-10-25.md.

## Testit

Koodimuutoksen jälkeen aja nämä ja korjaa, kunnes ne menevät läpi (kokoelman verify.py lisäksi):

```
python -X utf8 tests/test_avaa.py    # kerros: tests/tapaukset.txt (13 kuulohavaintoa ja lisätapaukset)
python -X utf8 tests/test_nvda.py    # NVDA-kytkentä tyngillä + käyttöohjeen merkkijonot
android/gradlew -p android :kerros:test   # Kotlin-kerros samoja tapauksia vasten
```

Kerroksen sääntömuutos tehdään molempiin toteutuksiin (`avaa.py` ja `android/kerros/.../Avaaja.kt`)
samassa commitissa. Sen jälkeen aja vertailu, joka vaatii toteutuksilta saman tuloksen:

```
python -X utf8 tests/vertailukorpus.py
android/gradlew -p android :kerros:test -Plausu.vertailu=../build/tyo/vertailu.tsv
```

Gradle tarvitsee `JAVA_HOME`:n (Android Studion `jbr`). Android-moottorin rakennus ja
laitetarkistus: docs/ANDROID.md › Porttaus.

Python on `$LOCALAPPDATA/Programs/Python/Python312/python.exe` (ei paljasta `python`-kutsua).

## Säännöt

- **Ei arvailua.** Kun sääntö ei tunnista tapausta varmasti, teksti jää ennalleen. Uusi sääntö
  saa testin sekä tapaukselle, jonka se avaa, että tapaukselle, jota se ei saa koskea.
- **Ajon aikana vain hakuja.** Taivutusmuodot tuotetaan käännösvaiheessa (build/). Ajonaikainen
  koodi on puhdasta Pythonia ilman riippuvuuksia, koska se ajetaan NVDA:n Pythonissa.
- **Itu luetaan, ei muokata.** build_tables.py lukee `../SanaMix/data/wordforms.txt`:n.
- **Generoitu data versioidaan** (`addon/globalPlugins/lausu/data/`). Rakenna se uudelleen
  build/-skripteillä, älä muokkaa käsin. rakenna_sanapuu.py todentaa puun koko taulukkoa vasten.
- **Käyttöohje** on `addon/doc/fi/readme.html`. Se päivitetään samassa commitissa kuin toiminto,
  ja test_nvda.py tarkistaa, että ohjeen nimeämät komento, luokka ja ilmoitukset ovat koodissa.
- **NVDA:n asetuksiin ei kosketa eikä lisäosaa asenneta** ilman Tommin kuittausta. Asennus ja
  laiteajo ovat Tommin todennusta.
- **Lisenssi GPLv3** (päätös 25.10.2026, docs/KOKEILU-2026-10-25.md › Lisenssireitti).
