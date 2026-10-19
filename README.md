# JPTV

Egen TV-app för Android TV / Google TV (Nvidia Shield, TCL m.fl.) som samlar Plex-biblioteket och IPTV-kanaler i ett gränssnitt.

**Status:** används dagligen på Shield och TCL. Nya versioner byggs automatiskt och appen uppdaterar sig själv.

## Vad som finns

| Del | Vad den gör |
| --- | --- |
| Plex-inloggning | Appen visar en kod, du skriver in den på [plex.tv/link](https://plex.tv/link). Servern hittas automatiskt, lokal adress först. |
| Hemskärm | Live nu, Fortsätt titta och nyligen tillagt. Håll inne OK på en titel för meny (detaljer, markera sedd, ta bort från Fortsätt titta, gå till serien). Klocka i hörnet. |
| Bibliotek | Rutnät med sortering (A–Ö, tillagt, år, betyg) och filter för osett. Serier visas med säsongsflikar; hela säsonger kan markeras som sedda. **Skanna** ber Plex leta efter nya filer (ett bibliotek, eller alla under Inställningar) och laddar om när servern är klar. |
| Sök | Söker samtidigt i Plex, bland kanalerna och i TV-guiden. |
| Uppspelning | **Direct Play** först. Klarar TV:n inte filen – t.ex. DTS-ljud på TCL – byter appen automatiskt till Plex-omkodning (H.264 + AAC). Hoppa över intro, nästa avsnitt vid eftertexterna och "Tittar du fortfarande?" efter tre avsnitt i rad. |
| Ljud och undertexter | Valet sparas i Plex och per serie, så nästa avsnitt startar med samma språk. **Sök undertexter** hämtar från internet via din Plex-server (OpenSubtitles m.fl.). Externa .srt kan synkas ±0,1 s åt gången. |
| Alternativ | Sovtimer (15–120 min eller "när det här är slut"), bildformat (anpassa/fyll/zooma) och teknisk info (upplösning, codec, HDR, bithastighet, buffert). |
| Live-TV | M3U- eller Xtream-lista med grupper, favoriter, senaste kanaler, kanalsök och dolda grupper. TV-guide (XMLTV) med nu/sedan och ett 3-timmarsrutnät. Kan starta direkt i senaste kanalen. |
| Mobilen som tangentbord | Under Inställningar visas en QR-kod. Öppna den i mobilen och klistra in M3U- och guideadressen där istället för att skriva med fjärren. |

## Fjärrkontrollen

| Knapp | Film/avsnitt | Live-TV |
| --- | --- | --- |
| OK | Paus / spela (hoppar över intro när det visas) | Kanallista (eller försök igen vid fel) |
| Håll inne OK | – | Alternativ: favorit, ljud, undertext, sovtimer, bildformat, info |
| ◀ / ▶ | −10 s / +30 s (håll inne för snabbare) | Förra kanalen / kanalinfo |
| ▲ | Ljud och undertexter | Nästa kanal |
| ▼ | Alternativ: sovtimer, bildformat, info | Föregående kanal |
| Siffror | – | Kanalnummer |
| Tillbaka | Avsluta, position sparas i Plex | Stäng panel / tillbaka till kanallistan |

## Installera på Shield eller TCL

**Första gången (en gång per TV):**

1. Installera appen **Downloader** (by AFTVnews) från Play Butik på TV:n.
2. Öppna Downloader och skriv in adressen:
   `https://github.com/thesummerboat/jptv/releases/latest/download/jptv.apk`
3. Tillåt Downloader att installera appar när TV:n frågar, och installera JPTV.

**Sedan uppdaterar JPTV sig själv.** Appen kollar GitHub vid start (och var sjätte timme). Finns en ny version visas en ruta på hemskärmen – tryck **Uppdatera nu** och bekräfta. Första gången ber TV:n dig tillåta att JPTV installerar appar. På Shield (Android 11) behövs ett OK per uppdatering; på nyare Google TV kan det gå helt utan fråga.

Varje push till `main` bygger en ny version i GitHub Actions och publicerar den under **Releases** som `jptv.apk`.

## Signering

Alla versioner signeras med samma nyckel, annars vägrar Android installera uppdateringen ovanpå den gamla. Nyckeln ligger **krypterad** i repot (`app/jptv.keystore.enc`). Lösenordet finns bara som hemligheten `JPTV_SIGNING_PASSWORD` under *Settings → Secrets and variables → Actions*, så den publika koden räcker inte för att signera en falsk JPTV.

Spara lösenordet på säkert ställe (t.ex. Bitwarden). Tappar du det måste JPTV avinstalleras och installeras om en gång med en ny nyckel.

## Bygga själv

Öppna mappen i Android Studio, eller kör `./gradlew assembleDebug`. Kräver JDK 17 och Android SDK 35. Lokala byggen signeras med Android Studios vanliga debug-nyckel och kan inte uppdatera versionen från GitHub.

## Teknik

Kotlin · Jetpack Compose for TV · Media3/ExoPlayer · OkHttp · Coil. Plex-API enligt [developer.plex.tv](https://developer.plex.tv).

## Nästa steg

- Förhandsbilder när man spolar (Plex index-bilder)
- Påminnelser från TV-guiden, timeshift/catch-up där leverantören stöder det
- Flera profiler, multiview
- Automatisk undertextsynk

## Bra att veta

- IPTV-källan måste vara laglig. Appen är bara en spelare och innehåller inga källor.
- Plex-token sparas i appens privata lagring på TV:n.
