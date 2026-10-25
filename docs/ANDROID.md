# Lausu Androidille

Tila 25.10.2026: tie on auki. Koe on ajettu mittausohjelmalla ja TalkBackilla (ks. Kokeen tulos).
Kerros on portattu Kotlinille ja kytketty moottoriin (ks. Porttaus). Lausu on ajettu TalkBackilla
kerran, ja sen havainnoista on korjattu kolme (ks. Lausun TalkBack-ajo).

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

Koodi oli kansiossa `koe/android-tts/`, joka poistettiin moottorin valmistuttua (Tommin päätös
25.10.2026). Sen voi palauttaa commitista `d3a903e`. Mukana oli kaksi osaa:

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

### TalkBack-ajo (25.10.2026)

Tommi asetti Pixelissä Lausun (koe) ensisijaiseksi puhemoottoriksi ja käytti TalkBackia
asetuksissa ja Jakon Moskassa. Kuulohavainnot:

- **Ääni** kuului samalta kuin Googlen ääni.
- **Viivettä** ei huomannut.
- **Keskeytys** toimi, eli puhe katkesi pyyhkäistäessä.

Moottorin loki samasta ajosta (159 TalkBackin lausumaa):

- 122 lausumassa tuli ääntä. Ensimmäisen äänipalan mediaani oli 42 ms ja suurin 265 ms, ja suurin
  osui ensimmäiseen lausumaan moottorin vaihdon jälkeen. Luku mitataan moottorin sisällä
  lausuman saapumisesta, eli se ei ole sama mittari kuin mittausohjelman taulukossa.
- 37 lausumaa jäi ilman ääntä. Osa oli TalkBackin lähettämiä tyhjiä tekstejä, ja osa keskeytettiin
  ennen ensimmäistä palaa.
- 15 lausumaa keskeytettiin kesken. Moottori lopetti 0,5–6,8 ms:ssa pysäytyksestä.
- Googlen virheitä ei tullut. Kaikki lausumat tulivat kielellä `fin-FIN`, ja TalkBackin
  puhenopeus näkyi moottorille arvona 85.

## Mitä siirtyy ja mitä kirjoitetaan uudelleen

- **Siirtyy sellaisenaan:** käännösvaiheen data (`sijat.dawg`, `lukusanat.json`, verbimuodot) ja
  testitapaukset (`tests/tapaukset.txt`), jotka käyvät määrittelyksi.
- **Kirjoitetaan uudelleen:** `avaa.py` ja `sanapuu.py` Kotlinilla, koska Androidissa ei ole
  Pythonia. Testit ajetaan molemmissa toteutuksissa samoja tapauksia vasten.

## Päätös: `android/`-kansio tähän repoon (25.10.2026)

Tommin päätös kokeen jälkeen. Syy on jaettu data ja jaetut testit. Molemmat toteutukset lukevat
saman datan (`addon/globalPlugins/lausu/data/`) ja samat testitapaukset (`tests/tapaukset.txt`),
joten kumpaakaan ei kopioida.

Rakenne:

- `android/kerros/` on pelkkää JVM-Kotlinia (`Avaaja`, `Sanapuu`). Testit ajetaan JUnitilla ilman
  laitetta, ja ne lukevat samat tapaukset kuin `tests/test_avaa.py`.
- `android/moottori/` on Android-sovellus eli puhemoottori. Se pohjautuu kokeen
  `ValitysPalvelu`un ja käyttää seuraa-tapaa. Data pakataan sovellukseen suoraan
  `addon/`-kansiosta.

## Porttaus (25.10.2026)

`avaa.py` ja `sanapuu.py` on kirjoitettu Kotlinilla (`android/kerros/`: `Avaaja.kt`,
`Sanapuu.kt`). Säännöt, järjestys ja nimet seuraavat Python-toteutusta. Yksi ero on tehty
tarkoituksella: Javan `\w`, `\d`, `\s` ja `\b` ovat ASCII-luokkia, joten ne on kirjoitettu auki
Unicode-ominaisuuksina samoiksi kuin Pythonissa.

Todennus:

| Mitä | Missä | Tulos |
|---|---|---|
| `tests/tapaukset.txt` (125 tapausta) | JVM (JUnit) | 125/125 |
| Vertailukorpus, 20 000 generoitua syötettä Python-tuloksineen | JVM (JUnit) | 20 000/20 000 samoja |
| Samat kaksi tiedostoa Androidin omalla regex-moottorilla | Pixel 8a | 125/125 ja 20 000/20 000 |
| Lause puhuttuna Lausun moottorin läpi äänenvoimakkuudella 0 | Pixel 8a | avautui, ääni tuli |

Vertailukorpus tuotetaan `tests/vertailukorpus.py`:llä. Se kertoo vain, että toteutukset ovat
samat, ja oikean tuloksen määrittelee `tapaukset.txt`. Korpuksesta 14 548 syötettä 20 000:sta
muuttui avauksessa, eli se käy läpi muutakin kuin ennalleen jääviä tapauksia.

Porttaus löysi Pythonista yhden vian. Yli biljoonan euron summa tuli muodossa "None euroa",
koska lukusanaa ei ollut. Nyt summa jää ennalleen, ja tapaus on `tapaukset.txt`:ssä.

Moottori (`android/moottori/`, sovellus "Lausu", paketti `fi.tommi.lausu`) lataa kerroksen
käynnistyessään (Pixelillä 31 ms) ja avaa vain suomenkieliset lausumat. Jos kerros ei lataudu,
teksti kulkee ennallaan. Kehitysversio kirjaa lausuman tekstin ennen ja jälkeen avauksen
lokiin (tagi `Lausu`). Julkaisuversio ei kirjaa, koska ruudunlukijan teksti on käyttäjän dataa.
Laitetarkistus ajetaan `Tarkistus`-aktiviteetilla adb:llä, ja ohje on sen tiedostossa.

## Lausun TalkBack-ajo (25.10.2026)

Tommi käytti TalkBackia Pixelissä Lausu-moottorilla uutissivuilla, Ilmatieteen laitoksen
sääsivulla ja verkkokaupassa. Moottorin lokiin tuli 164 lausumaa. Ensimmäisen äänipalan mediaani
oli 58 ms ja suurin 267 ms (115 lausumaa, joissa tuli ääntä). Luku mitataan moottorin sisällä
lausuman saapumisesta, ja se sisältää avauksen.

Tommin kuulohavainnot ja mitä niille tehtiin:

- **vrk ja kk** jäivät avaamatta ("15 vrk", "12 kk", "3 €/kk"). Ne on lisätty yksiköiksi
  (`build/build_tables.py`), eli "12 kk" kuuluu nyt "kaksitoista kuukautta". Lisäksi aikayksikkö
  jakoviivan jälkeen luetaan inessiivissä: "3 €/kk" kuuluu "kolme euroa kuukaudessa" ja "50 km/h"
  kuuluu "viisikymmentä kilometriä tunnissa". Muu jakoviiva jää ennalleen ("3 €/kpl").
- **Sääsivun päiväys.** TalkBack sai tekstin "perjantaina 10 heinäkuuta" ilman pistettä, ja Lausu
  avasi sen perusluvuksi ("kymmenen heinäkuuta"). Kuukauden nimen edellä luku luetaan nyt
  päiväykseksi myös ilman pistettä ("kymmenes heinäkuuta"). Jos päiväys ei kelpaa (32 heinäkuuta),
  luku jää ennalleen eikä sitä lueta lukumääräksi.

Muutokset ovat molemmissa toteutuksissa, ja niillä on testit sekä avattavalle tapaukselle että
tapaukselle, jota sääntö ei saa koskea (`tests/tapaukset.txt`, 132 tapausta). Vertailukorpus
laajennettiin uusiin kuvioihin, ja 30 000 syötettä antoi saman tuloksen Pythonissa, JVM:llä ja
Pixelillä.

Lokista näkyi myös kohtia, joita Tommi ei maininnut:

- **Kauttaviiva avautui puoliksi** (korjattu). "2/5 ryhmässä" tuli muotoon "kaksi/5 ryhmässä",
  koska luvun alun sääntö ei salli edellä kauttaviivaa. Tommi tulkitsi merkinnän vaiheeksi,
  arvosanaksi tai osamääräksi ja hyväksyi luennan "kaksi kautta viisi". Nyt kahden kokonaisluvun
  välinen kauttaviiva luetaan "kautta". Ketju (2024/10/05), kaksoispistepääte (3/4:n), etunolla
  ja desimaaliluku (1,5/2) jäävät kokonaan ennalleen eivätkä puoliksi. Sama sääntö korjaa
  Superjatsin SJ-5-esimerkin: "Heitto 1/3" kuuluu "heitto yksi kautta kolme" eikä kolmasosana.
  Tommi kuunteli korjauksen TalkBackilla ja totesi sen toimivaksi (25.10.2026).

Seuraavia ei ole korjattu:

- **Kellonaika ilman sanaa "klo"** ("11:47", "22:44") jää ennalleen, ja Googlen ääni lukee sen
  itse.
- **Hinta kahdessa osassa.** Verkkokauppa antoi hinnan TalkBackille kahtena kohtana ("649," ja
  "00"), joten ensimmäinen avautui pilkun kanssa. Tämä on sivun rakenne eikä kerroksen vika.
