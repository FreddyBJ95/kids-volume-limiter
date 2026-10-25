# Kids Volume Limiter – Android

Kids Volume Limiter begrænser den maksimale medielydstyrke på børns Android-tablets. Hvis barnet skruer
højere op end grænsen (fx med de fysiske lydstyrkeknapper), sænker appen automatisk lydstyrken igen. Det
virker i alle apps, der afspiller medielyd: YouTube, Netflix, spil osv.

- Standardgrænse: **40 %** (kan vælges mellem 10 og 100 %)
- **Separat grænse for hver lydudgang:** De indbyggede højttalere begrænses altid. Hovedtelefoner (kabel/USB) og
  Bluetooth kan få deres egen grænse. Når et headset eller Bluetooth tilsluttes, viser forsiden grænsen for netop
  den udgang og lader forælderen sætte den med PIN. Uden en grænse røres deres lydstyrke ikke.
- **PIN-kode** på 4–6 cifre beskytter grænsen, slå-fra-knappen og indstillingerne.
- Kører i baggrunden som forgrundstjeneste og starter igen efter genstart af tabletten.
- Ingen server, konto, abonnement eller internetforbindelse.

> **Vigtigt:** Android lader ikke almindelige apps låse systemets lydstyrke. Appen er en *automatisk
> regulering*: den reagerer på hver ændring og sænker lyden igen med det samme (typisk inden for en
> brøkdel af et sekund). Den er ikke en systemmæssig spærring. Se [Kendte begrænsninger](docs/BEGRAENSNINGER.md).

## Leverancer

| Leverance | Hvor |
|---|---|
| APK til installation | GitHub-release [v1.0.0](../../releases/latest) (`KidsVolumeLimiter-1.0.0.apk`) |
| Kildekode (Capacitor + TypeScript) | [`src/`](src/), [`index.html`](index.html) |
| Native Kotlin-plugin og tjeneste | [`android/app/src/main/java/dk/kidsvolume/limiter/`](android/app/src/main/java/dk/kidsvolume/limiter/) |
| Android Studio-projekt | [`android/`](android/) |
| Tilladelser | [`docs/TILLADELSER.md`](docs/TILLADELSER.md) |
| Testvejledning og acceptkriterier | [`docs/TESTVEJLEDNING.md`](docs/TESTVEJLEDNING.md) |
| Kendte begrænsninger og kompatibilitet | [`docs/BEGRAENSNINGER.md`](docs/BEGRAENSNINGER.md) |

## Installation på tabletten

APK'en installeres direkte uden Google Play.

1. Hent APK'en på tabletten: Åbn repository'ets side **Releases** i tablettens browser, og tryk på
   `KidsVolumeLimiter-1.0.0.apk` under *Assets*. Filen kan også kopieres over med USB-kabel (mappen *Download*) eller Google Drive.
2. Åbn filen på tabletten med appen **Mine filer** (Samsung) eller **Filer** (Lenovo/Google) under *Downloads*.
3. Android spørger, om filappen må installere ukendte apps. Tryk **Indstillinger**, slå **Tillad fra denne kilde** til, og gå tilbage.
4. Tryk **Installér**. Hvis Google Play Protect advarer om en ukendt udvikler, så vælg **Flere detaljer › Installér alligevel**.
5. Åbn **Kids Volume Limiter**, og følg opsætningen:
   1. Formålet forklares.
   2. Opret en PIN-kode (4–6 cifre).
   3. Vælg maksimal lydstyrke (standard 40 %).
   4. Tillad notifikationer, og undtag appen fra batterioptimering. Der vises tips til Samsung og Lenovo.
   5. Aktivér beskyttelsen.
   6. Selvtesten kontrollerer, at lydstyrken kan reguleres, og måler forsinkelsen.
6. Slå **Tillad fra denne kilde** fra igen for filappen, når installationen er færdig.

Med en computer og USB-fejlfinding kan APK'en også installeres med:

```bash
adb install -r KidsVolumeLimiter-1.0.0.apk
```

### Anbefalede indstillinger på tabletten

- **Samsung:** *Indstillinger › Batteri › Grænser for baggrundsbrug*: Appen må ikke stå under "Apps i dvale"
  eller "Apps i dyb dvale". Tilføj den til "Apps, der aldrig sover".
- **Lenovo:** *Indstillinger › Apps › Kids Volume Limiter › Batteri*: vælg "Ubegrænset". På modeller med ZUI
  skal autostart og baggrundskørsel også tillades.
- **Alle:** Brug Androids egen skærmtid/forældrekontrol (Google Family Link eller Samsung Kids) til at
  forhindre barnet i at afinstallere eller tvangsstoppe appen. Det kan Kids Volume Limiter ikke selv.

## Byg projektet

### Krav

- Node.js 20 eller nyere
- JDK 21 (følger med Android Studio Ladybug/Meerkat eller nyere)
- Android SDK Platform 35 og Build-Tools 35 (installeres af Android Studio)

### Android Studio

```bash
npm install
npm run sync
```

`npm run sync` bygger web-brugerfladen (`dist/`) og kopierer den ind i Android-projektet. Åbn derefter mappen
`android/` i Android Studio (*File › Open*), vent på Gradle-synkroniseringen, og vælg *Build › Build App Bundle(s) / APK(s) › Build APK(s)*.
Kør `npm run sync` igen, hver gang noget under `src/` er ændret.

### Kommandolinje

```bash
npm run apk:release
```

APK'en lægges i `android/app/build/outputs/apk/release/app-release.apk`.

Unit-tests af grænseberegningen og valget af lydudgang køres fra mappen `android/` med `gradlew testReleaseUnitTest`.

> **Windows:** Android-byggeværktøjerne kan fejle ved meget lange stier. Ligger projektet dybt i en mappe,
> så flyt det til en kort sti, fx `C:\kvl`, eller slå lange stier til i Windows.

### Signering

Uden opsætning signeres release-build'et med Androids debug-nøgle fra den computer, der bygger
(`%USERPROFILE%\.android\debug.keystore`). Det er nok til direkte installation. **Opdateringer skal signeres
med samme nøgle**, ellers skal appen afinstalleres først, og så forsvinder PIN og indstillinger.

Til varig brug kan du lave din egen nøgle i Android Studio (*Build › Generate Signed App Bundle / APK › Create new*)
og oprette `android/keystore.properties`:

```properties
storeFile=../kvl-release.jks
storePassword=…
keyAlias=kvl
keyPassword=…
```

Filen og nøglen må ikke deles. De er udelukket i `.gitignore`.

### Afprøv brugerfladen i en browser

```bash
npm run dev
```

I browseren bruges en simulering i stedet for den native del. Tasterne **Pil op/ned** svarer til tablettens
lydstyrkeknapper, og **H** skifter mellem højttaler, hovedtelefoner og Bluetooth. Tilføj `?demo=klar` til adressen for at
springe opsætningen over (PIN `1234`).

## Sådan virker det

```
Fysisk knap ─► Android ændrer STREAM_MUSIC ─► hændelse ─► VolumeWatcher ─► VolumeLimiter.enforce()
                                                                           │
                                     er udgangen højttaler, og er lydstyrken over grænsen?
                                                                           │ ja
                                                       AudioManager.setStreamVolume(grænse)
```

**Hændelser (ingen polling).** [`VolumeWatcher`](android/app/src/main/java/dk/kidsvolume/limiter/volume/VolumeWatcher.kt)
bruger kun offentlige Android-API'er:

| Kilde | Formål | Forsinkelse |
|---|---|---|
| `MediaRouter.Callback.onRouteVolumeChanged` | Medielydstyrken er ændret (fx med knapperne) | Straks |
| `ContentObserver` på `Settings.System` | Sikkerhedsnet: Android gemmer lydstyrken her | ca. 0,5 s |
| `AudioDeviceCallback`, `ACTION_HEADSET_PLUG`, `ACTION_AUDIO_BECOMING_NOISY` | Hovedtelefoner/Bluetooth til- eller frakoblet | Straks + efterkontrol efter 0,7 s |
| `ACTION_SCREEN_ON`, `ACTION_USER_PRESENT` | Ekstra kontrol, når tabletten vækkes | Straks |
| Start af app/tjeneste, genstart, vagthund | Ekstra kontrol, når overvågningen (gen)aktiveres | – |

Hændelser, der kommer samtidig, samles til én kontrol. Appen ændrer kun lydstyrken, når den er **over** grænsen,
så dens egne ændringer ikke udløser en ny regulering.

**Grænsen** beregnes ud fra enhedens egne trin: `grænsetrin = ⌊maks. trin × procent / 100⌋` (mindst ét trin).
Med 15 trin og 40 % er grænsen trin 6. Den valgte procent overskrides altså aldrig.

**En grænse pr. lydudgang.** Android har en separat medielydstyrke for hver lydudgang. Appen finder den
aktuelle udgang med `AudioManager.getAudioDevicesForAttributes` (Android 13+, ellers ud fra de tilsluttede
enheder) og bruger grænsen for netop den udgang:

| Udgang | Standard | Kan ændres |
|---|---|---|
| Indbyggede højttalere | Altid begrænset, 40 % | Grænsen (10–100 %) |
| Hovedtelefoner (kabel, USB-C) | Ikke begrænset, lydstyrken røres ikke | Slå egen grænse til og vælg niveau |
| Bluetooth (hovedtelefoner, højttalere, høreapparater) | Ikke begrænset, lydstyrken røres ikke | Slå egen grænse til og vælg niveau |
| Andre (HDMI, dock osv.) | Ikke begrænset | – |

Når et headset eller Bluetooth er i brug, skifter forsidens grænse-kort til den udgang. Har den ingen grænse,
vises knappen *Sæt grænse for hovedtelefoner/Bluetooth* (kræver PIN). Alle tre grænser kan også ændres under
*Indstillinger › Grænser pr. lydudgang*.

**Baggrundsdrift.** [`VolumeLimiterService`](android/app/src/main/java/dk/kidsvolume/limiter/service/VolumeLimiterService.kt)
er en forgrundstjeneste af typen `specialUse` med en fast, lydløs notifikation og `START_STICKY`.
[`BootReceiver`](android/app/src/main/java/dk/kidsvolume/limiter/service/BootReceiver.kt) starter den efter
genstart og opdatering. En WorkManager-vagthund kører højst hvert 15. minut (Android samler kørslerne) og
genstarter tjenesten, hvis Android har stoppet den. Der er ingen timere eller wake locks i appens egen kode.

**PIN-kode.** [`PinManager`](android/app/src/main/java/dk/kidsvolume/limiter/security/PinManager.kt) gemmer kun
et tilfældigt salt og en PBKDF2-HMAC-SHA256-hash (120.000 iterationer) i appens private DataStore. Efter fem
forkerte forsøg spærres der i 30 sekunder, og spærringen fordobles ved hvert nyt forkert forsøg op til 15
minutter. En korrekt PIN låser indstillingerne op i 3 minutter (forlænges ved aktivitet), og de låses igen, når
appen forlades. Kun det at slå beskyttelsen **til** kan ske uden PIN.

## Projektstruktur

```
├── index.html, src/                 Web-brugerflade (TypeScript, ingen UI-framework)
│   ├── main.ts                      Opstart og navigation
│   ├── state.ts                     Status fra det native plugin
│   ├── plugin/                      Plugin-definitioner + browsersimulering
│   └── ui/                          Forside, indstillinger, opsætning, PIN-tastatur
├── android/                         Android Studio-projekt (Capacitor 7, Kotlin)
│   └── app/src/main/java/dk/kidsvolume/limiter/
│       ├── MainActivity.kt
│       ├── plugin/VolumeLimiterPlugin.kt   Capacitor-plugin (bro til brugerfladen)
│       ├── volume/                         VolumeLimiter, VolumeWatcher, AudioRoutes
│       ├── service/                        Forgrundstjeneste, boot-modtager, vagthund
│       ├── security/PinManager.kt
│       └── settings/SettingsRepository.kt  Android DataStore
└── docs/                            Tilladelser, test og begrænsninger
```

Færdige APK'er ligger ikke i git, men som filer på GitHub-releases.

**Teknologi:** Capacitor 7.6, TypeScript 5, Vite 6, Kotlin 2.1, Android Gradle Plugin 8.7, `minSdk 26`
(Android 8.0), `targetSdk 35` (Android 15), AndroidX DataStore 1.1 og WorkManager 2.10.
