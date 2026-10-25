# Kendte begrænsninger og kompatibilitet

## Teststatus for version 1.0.0

- Kotlin-koden og web-brugerfladen er bygget og typetjekket. Release-APK'en er bygget og har bestået Androids lint-kontrol.
- Brugerfladen og PIN-flowet er afprøvet i browsersimuleringen.
- **Appen er endnu ikke testet på fysiske tablets.** Testsagerne i [TESTVEJLEDNING.md](TESTVEJLEDNING.md) skal
  køres på Samsung Galaxy Tab A11 og Lenovo-tabletten, før appen tages i brug. Det gælder særligt forsinkelse,
  slukket skærm, genstart og lang inaktivitet.

## Grundlæggende begrænsninger i Android

**Ingen ægte lås af lydstyrken.** En almindelig app kan ikke forhindre Android i at skrue op. Kids Volume Limiter
reagerer på ændringen og sætter lydstyrken tilbage til grænsen. Derfor kan der i et meget kort øjeblik (typisk
under 0,2 sekunder) høres et enkelt trin over grænsen, før lyden sænkes. Kommer den hurtige hændelse ikke på en
bestemt enhed, fanges ændringen af sikkerhedsnettet efter ca. 0,5–1 sekund. Selvtesten viser, hvilken af de to
der bruges.

Den eneste måde at blokere knapperne helt på ville være en tilgængelighedstjeneste eller en
enhedsejer-app (device owner). Begge er bevidst fravalgt i version 1 (se [TILLADELSER.md](TILLADELSER.md)).

**PIN-koden beskytter kun appens egne indstillinger.** Den kan ikke forhindre, at nogen:
- afinstallerer appen,
- tvangsstopper den under *Indstillinger › Apps*,
- trykker *Stop* ved appen i listen over aktive apps (Android 13+),
- ændrer Androids batteriindstillinger for appen,
- sletter appens data. Det nulstiller også PIN-koden.

Brug Google Family Link, Samsung Kids eller en separat brugerprofil til barnet, hvis det skal forhindres.

**Glemt PIN-kode.** Der er ingen gendannelse. Slet appens data under *Indstillinger › Apps › Kids Volume Limiter
› Lagerplads › Ryd data*, og kør opsætningen igen.

**Kun systemets medielydstyrke.** Appen begrænser `STREAM_MUSIC`, som er den lydstyrke, knapperne styrer under
afspilning. Ringetone, alarmer og notifikationer er ikke omfattet. Apps, der forstærker lyden i deres egen
afspiller ud over systemets niveau, kan ikke begrænses.

**Hovedtelefoner og Bluetooth.** Som standard begrænses kun de indbyggede højttalere. Hovedtelefoner og
Bluetooth kan få hver deres grænse. Android har en separat lydstyrke for hver udgang, så en udgang uden grænse
røres ikke, når appen regulerer de andre. På Android 12 og ældre afgør appen udgangen ud fra de tilsluttede
enheder. Det kan tage op til 0,7 sekunder at genkende et skift mellem udgange.

**Bluetooth-enheders egne knapper.** Grænsen for Bluetooth styrer tablettens lydstyrke for Bluetooth. De fleste
nyere Bluetooth-hovedtelefoner synkroniserer deres egne lydstyrkeknapper med tabletten ("absolut lydstyrke"),
og så begrænses de også. Enheder uden den funktion har en separat forstærker, som ingen app kan styre. Her kan
barnet stadig skrue op på selve hovedtelefonerne. Det samme gælder hovedtelefoner med indbygget forstærker.

## Baggrundsdrift og Android-versioner

| Android | Betydning for appen |
|---|---|
| 8.0 (API 26) – mindstekrav | Baggrundstjenester kræver forgrundstjeneste med notifikation. Det bruger appen. |
| 12 (API 31) | Forgrundstjenester må normalt ikke startes fra baggrunden. Undtagelser, som appen bruger: genstart (`BOOT_COMPLETED`), app-opdatering og apps, der er undtaget fra batterioptimering (vagthunden). |
| 13 (API 33) | Notifikationer kræver tilladelse. Listen "Aktive apps" giver mulighed for at stoppe appen. Præcis aflæsning af lydudgangen er mulig. |
| 14 (API 34) | Tjenester skal have en type. Appen bruger `specialUse`. Brugeren kan skjule notifikationen, men tjenesten kører videre. |
| 15 (API 35) – målversion | `BOOT_COMPLETED` må ikke starte visse tjenestetyper (fx `mediaPlayback`). `specialUse` er ikke omfattet. |

**Efter genstart** starter overvågningen først, når tabletten er låst op første gang. Før det sender Android ikke
`BOOT_COMPLETED`.

**Vagthunden** kører via WorkManager højst hvert 15. minut. Android kan udskyde den yderligere i dyb dvale. Den
kan kun genstarte tjenesten fra baggrunden, hvis appen er undtaget fra batterioptimering. Ellers sker
genstarten, næste gang appen åbnes.

**Google Play.** `specialUse` kræver en begrundelse ved udgivelse på Google Play. Version 1 er lavet til direkte
installation af APK.

## Producentforskelle

Implementeringen bruger kun standard-Android og ingen Samsung- eller Lenovo-specifikke funktioner.

### Samsung Galaxy Tab A11 (One UI)

- Leveres med en nyere Android-version, forventeligt Android 15 / One UI 7 eller nyere. Tjek den under
  *Indstillinger › Om tablet › Softwareoplysninger*.
- One UI lægger apps i "dvale" eller "dyb dvale" og kan automatisk gøre det med apps, der ikke bruges. Appen bør
  stå under *Indstillinger › Batteri › Grænser for baggrundsbrug › Apps, der aldrig sover*, og have batteriforbrug
  "Ubegrænset".
- Samsung har en indbygget **Grænse for medielydstyrke** med PIN: *Indstillinger › Lyde og vibration › Lydstyrke ›
  ⋮ › Grænse for medielydstyrke*. Den er en ægte systemgrænse og kan bruges som ekstra lag på Samsung. Kids
  Volume Limiter er ikke afhængig af den.
- *Separat applyd* (Sound Assistant / "Separat app-lyd") kan sende én apps lyd til en anden udgang. Den
  kombination er ikke understøttet.

### Lenovo-tablets (model endnu ukendt)

- De fleste Lenovo Tab-modeller kører næsten standard-Android. Nogle modeller har Lenovos ZUI med ekstra
  "Baggrundsstyring" og "Autostart". Tillad begge for appen, hvis menuerne findes.
- Tjek Android-versionen under *Indstillinger › Om tablet*. På Android 12 og ældre bruges den forenklede
  genkendelse af lydudgangen (se ovenfor).
- Antallet af lydstyrketrin varierer mellem modeller. Appen beregner grænsen ud fra enhedens egne trin og viser
  dem under *Diagnostik*.

### Dokumentér forskelle

Notér for hver tablet i [testskemaet](TESTVEJLEDNING.md#resultatskema): Android-version, antal lydstyrketrin,
målt forsinkelse og udløser (knap/lydstyrke eller systemindstilling), og om tjenesten overlever lang inaktivitet
og genstart med de anbefalede batteriindstillinger.
