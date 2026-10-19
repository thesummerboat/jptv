# JPTV

Egen TV-app för Android TV / Google TV (Nvidia Shield, TCL m.fl.) som samlar Plex-biblioteket och IPTV-kanaler i ett gränssnitt.

**Status: Fas 0 – spik.** Målet är att bevisa att en Plex-film och en IPTV-kanal spelar smidigt i en egen app, på både Shield och TCL.

## Vad som finns

| Del | Vad den gör |
| --- | --- |
| Plex-inloggning | Appen visar en kod, du skriver in den på [plex.tv/link](https://plex.tv/link). Servern hittas automatiskt, lokal adress först. |
| Hemskärm | Fortsätt titta + nyligen tillagt per bibliotek. Knappar till varje bibliotek, Live-TV och Inställningar. |
| Bibliotek | Alla filmer/serier som rutnät. Serier öppnas som avsnittslista, fokus hamnar på första osedda avsnittet. |
| Uppspelning | **Direct Play** först (filen spelas som den är). Klarar TV:n inte filen – t.ex. DTS-ljud på TCL – byter appen automatiskt till Plex-omkodning (H.264 + AAC). Position och sett-status rapporteras tillbaka till Plex. |
| Live-TV | M3U- eller Xtream-lista, grupper till vänster, kanaler till höger. Film/serier i listan hoppas över. |
| Kanalbyte | ▲/▼ eller kanal-knapparna byter kanal i helskärm. OK visar info, Tillbaka går till listan. |

## Fjärrkontrollen

| Knapp | Film/avsnitt | Live-TV |
| --- | --- | --- |
| OK | Paus / spela | Visa kanalinfo (eller försök igen vid fel) |
| ◀ / ▶ | −10 s / +30 s | Visa kanalinfo |
| ▲ / ▼ | Visa tid | Nästa / föregående kanal |
| Tillbaka | Avsluta, position sparas i Plex | Tillbaka till kanallistan |

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

## Nästa steg (Fas 1)

- EPG/guide från XMLTV
- Favoritkanaler och kanalnummer som sparas
- Detaljsida för filmer med "Fortsätt" / "Börja om"
- Val av ljud- och textspår

## Bra att veta

- IPTV-källan måste vara laglig. Appen är bara en spelare och innehåller inga källor.
- Plex-token sparas i appens privata lagring på TV:n.
