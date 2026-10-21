# JPTV

Egen TV-app för Android TV / Google TV (Nvidia Shield, TCL m.fl.) som samlar Plex-biblioteket och IPTV-kanaler i ett gränssnitt.

**Status:** används dagligen på Shield och TCL. Nya versioner byggs automatiskt och appen uppdaterar sig själv.

## Vad som finns

| Del | Vad den gör |
| --- | --- |
| Plex-inloggning | Appen visar en kod, du skriver in den på [plex.tv/link](https://plex.tv/link). Servern hittas automatiskt, lokal adress först. |
| Hemskärm | Fortsätt titta, Nytt per bibliotek, **Topplista IMDb**, Plex egna rader (högst betyg, nyligen släppta, återupptäck, mer i genre …). Håll inne OK på en titel för meny. Små affischer utan text och en stor bakgrund: logotyp, märken och IMDb-betyg för titeln som har fokus står i den stora rutan. När appen öppnas (och när fokus står i menyn överst) visar den stora rutan **fem utvalda rekommendationer** som byter av sig själva, med ▶ Spela / Mer info; ◀ ▶ på knapparna bläddrar själv bland dem. Live-TV har sin egen flik. Står du kvar på en titel i tre sekunder spelas **trailern ljudlöst i bakgrunden**, som på Netflix (kan stängas av). |
| Bibliotek | Två lägen per bibliotek: **Bläddra** – som hemskärmen, med fem utvalda i den stora rutan och rader (Fortsätt titta, Nytt, Rekommenderat för dig, Eftersom du såg …, Topplista IMDb, Plex egna rader och de största genrerna) – och **Alla titlar**, ett rutnät. Kategorierna (genrerna) ligger överst i båda; i Bläddra öppnar en kategori rutnätet med den genren. I rutnätet ligger **kategorierna** (Alla, genrerna och Samlingar) direkt överst. Rutnät med sortering (A–Ö, nytt, år, IMDb) och filter för osett. Sorterat A–Ö finns en **bokstavslist** vid kanten: bläddra så följer rutnätet med, OK hoppar in på bokstaven. Bibliotek kan döljas (t.ex. 4K) eller bli ★ favoriter under Inställningar. **Seriesidan** visar säsongerna till vänster och avsnitten med bild, längd, IMDb och handling till höger, plus "▶ Fortsätt S2 E5"; hela säsonger kan markeras som sedda. **Detaljsidan** har samma stil som hemskärmen med trailern ljudlöst i bakgrunden. **Skanna** ber Plex leta efter nya filer (ett bibliotek, eller alla under Inställningar) och laddar om när servern är klar. |
| Sök | Söker samtidigt i Plex, bland kanalerna och i TV-guiden. |
| Rekommendationer | "Rekommenderat för dig" (filmer och serier) och "Eftersom du såg …", räknat på TV:n från din Plex-tittarhistorik: genre, regissör, manus, skådespelare och årtionde i det du sett, nyligen sett väger mest, plus IMDb-betyget. Håll inne OK → **Inte intresserad** för att styra. Historiken lämnar aldrig hemmanätet. |
| Betyg och märken | IMDb-betyg på filmer, serier och avsnitt (från Plex eller IMDb:s öppna betygsfil, hämtas en gång i veckan). RT och TMDB när Plex har dem. Märken för 4K, Dolby Vision, HDR10, Atmos och 5.1/7.1. Plex logotyper i stället för titeltext. |
| Trailers | ▶ Trailer på detaljsidan och i serievyn när Plex har en (lokal fil eller Plex egna med Plex Pass). Påverkar inte Fortsätt titta. |
| Ledmotiv | Seriens **ledmotiv** spelas lågt när seriesidan öppnas, som i Plex, och tonas ut när du lämnar den. Inte igen när du kommer tillbaka från ett avsnitt. Volym Lågt/Mellan, eller Av, under Inställningar. |
| Bild | **Automatisk bildfrekvens**: TV:n byter till 23,976/24/25/50/59,94 Hz efter filmen eller kanalen. Spolning med förhandsbild när Plex har gjort index-bilder. |
| Uppspelning | **Direct Play** först. Klarar TV:n inte filen – t.ex. DTS-ljud på TCL – byter appen automatiskt till Plex-omkodning (H.264 + AAC). Hoppa över intro (med en automatisk omsynk av ljudet en sekund efter hoppet), nästa avsnitt vid eftertexterna och "Tittar du fortfarande?" efter tre avsnitt i rad. |
| Ljud och undertexter | Valet sparas i Plex och per serie, så nästa avsnitt startar med samma språk. **Sök undertexter** hämtar från internet via din Plex-server (OpenSubtitles m.fl.). Externa .srt kan synkas ±0,1 s åt gången. |
| Alternativ | Sovtimer (15–120 min eller "när det här är slut"), bildformat (anpassa/fyll/zooma) och teknisk info (upplösning, codec, HDR, bithastighet, buffert). |
| Live-TV | M3U- eller Xtream-lista med grupper, favoriter, senaste kanaler, kanalsök och dolda grupper. TV-guide (XMLTV) med nu/sedan och ett 3-timmarsrutnät. Kan starta direkt i senaste kanalen. **Pausa live**: ▶‖ (eller Pausa i alternativen) pausar kanalen; den spelas in på TV:n under tiden (samma ström) och fortsätter där du pausade. ⏪/⏩ spolar 30 s, "Till live" hoppar fram. **Påminnelser**: håll OK på ett kommande program i guiden → 🔔; när det börjar kommer en ruta med "Byt kanal". **Bild-i-bild**: Tillbaka från en kanal lämnar den i ett hörn medan du bläddrar i guiden och menyerna – samma ström, aldrig två. OK på en annan kanal i listan eller guiden byter kanal i rutan; OK igen på samma kanal (eller ▶‖) tar den till helskärm. Håll inne Tillbaka (var som helst), Meny ≡, Stopp, Tillbaka på hemskärmen eller att starta något från Plex stänger den. |
| **Telefonen** | Samma APK som på TV:n; på en telefon visas ett pekgränssnitt: Hem, Live-TV (grupper, kanaler med nu/sedan och dagens program med 🔔-påminnelser), Bibliotek (kategorier, sortering), seriesida med avsnittsbilder, detaljsida, Sök och Inställningar. Film och avsnitt spelas liggande i helskärm med Media3:s kontroller (spola, ljud, undertexter), Hoppa över intro och Nästa avsnitt. Hemma spelas originalet; hemifrån kodar Plex om till vald kvalitet (Inställningar). Kräver att fjärråtkomst är på i Plex. |
| **Casta** | Castikonen på telefonen hittar Chromecast och Google TV på samma wifi (även hotellets). Plex skickas som omkodad HLS med en tillfällig Plex-nyckel (kontots nyckel lämnar aldrig telefonen) och fortsätter där telefonen var; telefonen blir fjärr med paus, ±spola och stopp. Live-TV castas som HLS om leverantören tillåter det. |
| Mobilen som tangentbord | Under Inställningar visas en QR-kod. Öppna den i mobilen och klistra in M3U- och guideadressen där istället för att skriva med fjärren. |

## Webbappen (datorn hemma)

**JPTV Web** är samma sak i webbläsaren: en Docker-container på Unraid som du öppnar på datorn. Bara hemmanätet; den har ingen inloggning och ska inte öppnas mot internet.

| Del | Vad den gör |
| --- | --- |
| Hem och bibliotek | Som på TV:n: fem utvalda i den stora rutan, rader med Fortsätt titta, Nytt, Rekommenderat för dig, Eftersom du såg …, Topplista IMDb och Plex egna rader. Musen över en affisch visar titeln i den stora rutan; trailern spelas ljudlöst efter tre sekunder. Bibliotek med Bläddra och Alla titlar (sortering, genre, osett, samlingar, bokstavslist). |
| Detaljer och serier | Logotyp, betyg, märken, handling, medverkande, Liknande. Seriesidan med säsonger till vänster och avsnitt med bilder till höger, Slumpvis, ledmotiv. |
| Spelaren | Plex kodar om till H.264/AAC i HLS (videon kopieras om webbläsaren klarar den). Fortsätter där du var, Hoppa över intro, Nästa avsnitt, ljud, undertexter (bränns in) och kvalitet. Tangenter: mellanslag, ← →, ↑ ↓, F, M, N, Esc. Tittartiden rapporteras till Plex, så Fortsätt titta stämmer på TV:n. |
| Live-TV | Samma M3U/Xtream-lista och guide som på TV:n: grupper, favoriter, senaste, sök, nu/sedan och kommande program. **En kanal åt gången**: servern läser kanalen med ffmpeg (ljudet blir AAC, bilden kopieras; HEVC och liknande kodas om automatiskt) och stänger den när ingen tittar. Bild-i-bild fortsätter medan du bläddrar i Plex. |
| TV-guide | Tidslinje med nu-linje; klick på ett pågående program startar kanalen. |

Webbappen och TV:n är två olika spelare: tittar du på live-TV på båda samtidigt är det två strömmar hos leverantören.

### Installera på Unraid

1. *Docker → Add Container*
2. **Name:** `jptv-web` · **Repository:** `ghcr.io/thesummerboat/jptv-web:latest`
3. *Add another Path, Port …* → **Port**: container `8099`, värd `8099`
4. *Add another Path, Port …* → **Path**: container `/config`, värd `/mnt/user/appdata/jptv-web`
5. *Apply*, öppna `http://<unraids-ip>:8099` på datorn, logga in med koden på plex.tv/link och klistra in spellistan under Inställningar.

Nya versioner byggs automatiskt (`web.yml`) när något i `web/` ändras; uppdatera containern i Unraid (*Check for updates*). Testerna körs i containern innan den publiceras. Lokalt: `cd web && pip install -r requirements.txt && JPTV_CONFIG=./config python -m app.main` (kräver ffmpeg).

## Fjärrkontrollen

| Knapp | Film/avsnitt | Live-TV |
| --- | --- | --- |
| OK | Paus / spela (hoppar över intro när det visas) | Kanallista (eller försök igen vid fel) |
| Håll inne OK | – | Alternativ: favorit, ljud, undertext, sovtimer, bildformat, info |
| ◀ / ▶ | Spola −10 s / +30 s med förhandsbild, hoppar när du släpper (håll inne för snabbare) | Förra kanalen / kanalinfo |
| ▲ | Ljud och undertexter | Nästa kanal |
| ▼ | Alternativ: sovtimer, bildformat, info | Föregående kanal |
| Siffror | – | Kanalnummer |
| Tillbaka | Avsluta, position sparas i Plex | Stäng panel / tillbaka, kanalen fortsätter i ett hörn (bild-i-bild) |
| Guide | – | Guiden med kanalen kvar i hörnet |
| OK i kanallistan/guiden med bild-i-bild | – | Byter kanal i rutan; OK igen på samma kanal = helskärm |
| ▶‖ i menyerna | – | Kanalen i hörnet tillbaka till helskärm |
| ▶‖ / ⏪ / ⏩ | Paus / spola | Pausa live / −30 s / +30 s (fram till live) |
| Håll OK i guiden | – | Påminnelse för ett kommande program |

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

Kotlin · Jetpack Compose for TV · Media3/ExoPlayer · OkHttp · Coil. Webbappen: Python/aiohttp, ffmpeg och hls.js, utan ramverk. Plex-API enligt [developer.plex.tv](https://developer.plex.tv).

## Nästa steg

- Påminnelser från TV-guiden, timeshift/catch-up där leverantören stöder det
- Flera profiler, multiview
- Automatisk undertextsynk

## Bra att veta

- IPTV-källan måste vara laglig. Appen är bara en spelare och innehåller inga källor.
- Plex-token sparas i appens privata lagring på TV:n.
