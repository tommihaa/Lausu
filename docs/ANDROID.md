# Lausu Androidille (idea 25.10.2026, ei päätös)

Tila: idea. Ennen kuin mitään koodataan, yksi koe ratkaisee, onko tie avoin (ks. Ensimmäinen koe).

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

## Mitä siirtyy ja mitä kirjoitetaan uudelleen

- **Siirtyy sellaisenaan:** käännösvaiheen data (`sijat.dawg`, `lukusanat.json`, verbimuodot) ja
  testitapaukset (`tests/test_avaa.py`), jotka käyvät määrittelyksi.
- **Kirjoitetaan uudelleen:** `avaa.py` ja `sanapuu.py` Kotlinilla, koska Androidissa ei ole
  Pythonia. Testit ajetaan molemmissa toteutuksissa samoja tapauksia vasten.

## Avoin päätös

Erillinen repo vai `android/`-kansio tämän repon sisällä. Päätetään kokeen jälkeen. Siihen
vaikuttaa, kuinka paljon dataa ja testejä jaetaan.
