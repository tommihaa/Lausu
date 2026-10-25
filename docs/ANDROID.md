# Lausu Androidille (idea 25.10.2026, ei päätös)

Tila: ensimmäinen koe ajettu 25.10.2026 mittausohjelmalla, ja tie on auki. TalkBack-ajo puuttuu
(ks. Kokeen tulos).

## Miksi

TalkBack taivuttaa suomen luvut väärin samalla tavalla kuin NVDA:n syntetisaattorit. Superjatsin
lähdekoodissa on siitä kirjattu esimerkki (`src/ui/locales/fi.ts`, kommentti SJ-5): "Heitto 1
kolmesta" kuului muodossa "yhdestä kolmesta", ja "1/3" kuului kolmasosana. Lausun kerros on tehty
juuri tällaista virhettä varten.

## Miksi NVDA-lisäosa ei riitä

- NVDA-lisäosa on Windowsin NVDA:n Python-koodia, eikä se toimi Androidissa.
- TalkBackissa ei ole lisäosarajapintaa, jolla puhetta voisi muokata ennen syntetisaattoria.
- Androidissa välikohta on puhemoottori (TTS-moottori). TalkBack lähettää tekstin valitulle
  moottorille. Oma moottori voisi avata tekstin ja välittää sen varsinaiselle äänelle (Googlen tai
  Samsungin moottori).

## Ensimmäinen koe

Toimiiko välitys toiseen moottoriin käytännössä? Selvitettävät asiat:

1. Voiko oma `TextToSpeechService` kutsua toista asennettua moottoria ja palauttaa sen äänen
   TalkBackille.
2. Viive: ruudunluvussa puheen on alettava heti. Mittaa aika tekstin saapumisesta äänen alkuun
   ja vertaa suoraan Googlen moottoriin.
3. Keskeytys: TalkBack katkaisee puheen jatkuvasti, kun käyttäjä liikkuu. Katkeaako välitetty
   ääni yhtä nopeasti.
4. Äänen valinta: voiko käyttäjä valita, minkä moottorin ja äänen Lausu käyttää.

Jos jokin näistä ei toimi, tie on suljettu, ja se kirjataan tähän.

## Kokeen tulos (25.10.2026)

Koodi on kansiossa `koe/android-tts/`. Se on kertakäyttöinen koe eikä repopäätös. Mukana on kaksi
osaa:

- `ValitysPalvelu` on puhemoottori, joka välittää tekstin Googlen moottorille ja syöttää sen äänen
  eteenpäin. Teksti kulkee kokeessa läpi muuttamatta, eli Lausun kerrosta ei ole vielä mukana.
- `Mittaus` on ohjelma, joka puhuu saman lauseen suoraan Googlella ja Lausun kautta.
  Äänenvoimakkuus on nolla, ja ohjelma käynnistetään adb:llä. Tulokset tulevat logcatiin
  tagilla `LausuKoe`.

Googlen ääni saadaan ulos vain `synthesizeToFile`-kutsulla. Kokeessa oli kolme tapaa lukea se:

- **putki:** Google kirjoittaa putkeen, ja ääni luetaan sitä mukaa kuin sitä tulee.
- **seuraa:** Google kirjoittaa tavalliseen tiedostoon, ja ääni luetaan perässä tiedoston
  kasvaessa.
- **tiedosto:** lause odotetaan valmiiksi ja luetaan vasta sitten. Tämä on vertailukohta.

Viive mitattiin `speak()`-kutsusta ensimmäiseen äänipalaan (`onAudioAvailable`). Luku on
mediaani, ja ensimmäinen kierros on jätetty pois lämmittelynä. Laite on Pixel 8a (Android API 37)
ja ääni Googlen paikallinen suomi `fi-FI-language` (24 000 Hz, 16 bittiä, mono).

| Moottori | Lyhyt lause (7 kierrosta) | Pitkä lause, 12 s puhetta (4 kierrosta) |
|---|---|---|
| Google suoraan | 13 ms | 14 ms |
| Lausu, seuraa | 20 ms | 20 ms |
| Lausu, tiedosto | 30 ms | 43 ms |
| Lausu, putki | 92 ms | 156 ms |

Lyhyt lause on "Heitto 1 kolmesta, painike". Tabletilla (SM-T970, API 33) sama mittaus antoi
Googlelle 6 ms ja seuraa-tavalle 10 ms.

Vastaukset kokeen neljään kysymykseen:

1. **Välitys toimii.** Oma `TextToSpeechService` voi kutsua Googlen moottoria, ja ääni kulkee
   eteenpäin kaikilla kolmella tavalla. Äänen tavumäärä oli sama kaikissa (124 800 tavua lyhyestä
   lauseesta). Muoto luetaan kerran oikean tiedoston otsakkeesta, koska putken otsake on tyhjä.
2. **Viivettä tulee Pixelillä noin 7 ms** seuraa-tavalla. Putki on hitain, vaikka sen piti olla nopein, ja
   syy jäi selvittämättä. Moottorin ensimmäinen lause käynnistyksen jälkeen kesti 1,75 s, koska
   moottori yhdistää silloin Googleen ja lukee äänen muodon. TalkBack pitää moottorin käynnissä,
   joten viive osuu vain ensimmäiseen lauseeseen.
3. **Keskeytys on Googlen tasoa.** Aika `stop()`-kutsusta `onStop`-kutsuun oli seuraa-tavalla
   10 ms ja Googlella suoraan 6–13 ms (mediaanit useasta ajosta). Moottori lopettaa lukemisen alle
   3 ms:ssa pysäytyksestä. Kaikissa tavoissa ja Googlella näkyi satunnaisia noin 95 ms:n
   poikkeamia, ja yhdessä ajossa kolmen Lausu-tavan mediaani oli niiden takia noin 97 ms. Syy jäi
   selvittämättä.
4. **Äänet näkyvät.** Moottori näkee Googlen äänet (633, suomeksi 3: kaksi paikallista ja yksi
   verkkoääni) ja voi asettaa niistä yhden. Valintaa käyttäjälle ei tehty kokeeseen.

Mittaamatta jäi TalkBack itse, koska se vaatii Lausun asettamista puhelimen puhemoottoriksi, ja
se on Tommin kuittaama asetusmuutos. Mittausohjelma käyttää samaa rajapintaa kuin TalkBack, mutta
korvin kuultava laatu ja TalkBackin oma käytös ovat vielä todentamatta.

## Mitä siirtyy ja mitä kirjoitetaan uudelleen

- **Siirtyy sellaisenaan:** käännösvaiheen data (`sijat.dawg`, `lukusanat.json`, verbimuodot) ja
  testitapaukset (`tests/test_avaa.py`), jotka käyvät määrittelyksi.
- **Kirjoitetaan uudelleen:** `avaa.py` ja `sanapuu.py` Kotlinilla, koska Androidissa ei ole
  Pythonia. Testit ajetaan molemmissa toteutuksissa samoja tapauksia vasten.

## Avoin päätös

Erillinen repo vai `android/`-kansio tämän repon sisällä. Päätetään kokeen jälkeen. Siihen
vaikuttaa, kuinka paljon dataa ja testejä jaetaan.
