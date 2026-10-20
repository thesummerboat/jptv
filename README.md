# JPTV

Egen TV-app för Android TV / Google TV (Nvidia Shield, TCL m.fl.) som samlar Plex-biblioteket och IPTV-kanaler i ett gränssnitt.

**Status:** används dagligen på Shield och TCL. Nya versioner byggs automatiskt och appen uppdaterar sig själv.

## Vad som finns

| Del | Vad den gör |
| --- | --- |
| Plex-inloggning | Appen visar en kod, du skriver in den på [plex.tv/link](https://plex.tv/link). Servern hittas automatiskt, lokal adress först. |
| Hemskärm | Live nu, Fortsätt titta, Nytt per bibliotek, **Topplista IMDb**, Plex egna rader (högst betyg, nyligen släppta, återupptäck, mer i genre …). Håll inne OK på en titel för meny. Logotyp, märken och IMDb-betyg för titeln som har fokus. Står du kvar på en titel i tre sekunder spelas **trailern ljudlöst i bakgrunden**, som på Netflix (kan stängas av). |
| Bibliotek | **Kategorierna** (Alla, genrerna och Samlingar) ligger direkt överst när du öppnar Filmer eller TV-serier. Rutnät med sortering (A–Ö, nytt, år, IMDb) och filter för osett. Bibliotek kan döljas (t.ex. 4K) eller bli ★ favoriter under Inställningar. Serier visas med säsongsflikar; hela säsonger kan markeras som sedda. **Skanna** ber Plex leta efter nya filer (ett bibliotek, eller alla under Inställningar) och laddar om när servern är klar. |
| Sök | Söker samtidigt i Plex, bland kanalerna och i TV-guiden. |
| Rekommendationer | "Rekommenderat för dig" (filmer och serier) och "Eftersom du såg …", räknat på TV:n från din Plex-tittarhistorik: genre, regissör, manus, skådespelare och årtionde i det du sett, nyligen sett väger mest, plus IMDb-betyget. Håll inne OK → **Inte intresserad** för att styra. Historiken lämnar aldrig hemmanätet. |
| Betyg och märken | IMDb-betyg på filmer, serier och avsnitt (från Plex eller IMDb:s öppna betygsfil, hämtas en gång i veckan). RT och TMDB när Plex har dem. Märken för 4K, Dolby Vision, HDR10, Atmos och 5.1/7.1. Plex logotyper i stället för titeltext. |
| Trailers | ▶ Trailer på detaljsidan och i serievyn när Plex har en (lokal fil eller Plex egna med Plex Pass). Påverkar inte Fortsätt titta. |
| Ledmotiv | Seriens **ledmotiv** spelas lågt när seriesidan öppnas, som i Plex, och tonas ut när du lämnar den. Inte igen när du kommer tillbaka från ett avsnitt. Volym Lågt/Mellan, eller Av, under Inställningar. |
| Bild | **Automatisk bildfrekvens**: TV:n byter till 23,976/24/25/50/59,94 Hz efter filmen eller kanalen. Spolning med förhandsbild när Plex har gjort index-bilder. |
| Uppspelning | **Direct Play** först. Klarar TV:n inte filen – t.ex. DTS-ljud på TCL – byter appen automatiskt till Plex-omkodning (H.264 + AAC). Hoppa över intro (med en automatisk omsynk av ljudet en sekund efter hoppet), nästa avsnitt vid eftertexterna och "Tittar du fortfarande?" efter tre avsnitt i rad. |
| Ljud och undertexter | Valet sparas i Plex och per serie, så nästa avsnitt startar med samma språk. **Sök undertexter** hämtar från internet via din Plex-server (OpenSubtitles m.fl.). Externa .srt kan synkas ±0,1 s åt gången. |
| Alternativ | Sovtimer (15–120 min eller "när det här är slut"), bildformat (anpassa/fyll/zooma) och teknisk info (upplösning, codec, HDR, bithastighet, buffert). |
| Live-TV | M3U- eller Xtream-lista med grupper, favoriter, senaste kanaler, kanalsök och dolda grupper. TV-guide (XMLTV) med nu/sedan och ett 3-timmarsrutnät. Kan starta direkt i senaste kanalen. **Bild-i-bild**: Tillbaka från en kanal lämnar den i ett hörn medan du bläddrar i guiden och menyerna – samma ström, aldrig två. OK på en annan kanal i listan eller guiden byter kanal i rutan; OK igen på samma kanal (eller ▶‖) tar den till helskärm. Tillbaka på hemskärmen, Stopp, eller att starta något från Plex stänger den. |
| Mobilen som tangentbord | Under Inställningar visas en QR-kod. Öppna den i mobilen och klistra in M3U- och guideadressen där istället för att skriva med fjärren. |

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

- Påminnelser från TV-guiden, timeshift/catch-up där leverantören stöder det
- Flera profiler, multiview
- Automatisk undertextsynk

## Bra att veta

- IPTV-källan måste vara laglig. Appen är bara en spelare och innehåller inga källor.
- Plex-token sparas i appens privata lagring på TV:n.
