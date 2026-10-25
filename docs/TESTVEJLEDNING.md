# Testvejledning

Testen udføres på **Samsung Galaxy Tab A11** og på **Lenovo-tabletten**. Notér resultaterne i skemaet nederst.

## Forberedelse

1. Installér APK'en, og gennemfør opsætningen (se [README](../README.md#installation-på-tabletten)).
2. Sæt grænsen til **40 %**.
3. Følg batteri-rådene i appen (*Indstillinger › Baggrundsdrift*).
4. Notér under *Indstillinger › Diagnostik*: producent, model, Android-version og "Lydstyrketrin på enheden"
   (fx `0–15 (grænse: trin 6)`).

### Værktøjer (valgfrit, men anbefalet)

Med en computer, USB-kabel og *Udviklerindstillinger › USB-fejlfinding* slået til kan appens log følges:

```bash
adb logcat -v monotonic -s VolumeLimiter VolumeLimiterService ServiceController
```

Hver automatisk sænkning logges som fx:
`Sænkede lydstyrken 8 -> 6 (knap/lydstyrke, 4 ms)`. Teksten i parentes er udløseren og appens egen behandlingstid.

Appen fører også en log over de seneste 20 sænkninger under *Indstillinger › Diagnostik*.

## Testsager

### T1 – Grundfunktion med fysiske knapper
1. Start en video i YouTube, og lad lyden komme fra de indbyggede højttalere.
2. Tryk gentagne gange på **lydstyrke op**.

**Forventet:** Lydstyrkebjælken kan kortvarigt vise et trin over grænsen, men falder straks tilbage til grænsen (40 % = trin 6 af 15).
Lyden bliver aldrig hængende over grænsen. Hold også knappen nede. Det samme skal ske.

### T2 – Andre apps med medielyd
Gentag T1 i Netflix (eller en anden streamingapp), et spil, en musikapp og en lokal video i Galleri/Fotos.

**Forventet:** Samme resultat som T1 i alle apps.

### T3 – Lydstyrke fra andre steder
Skru op fra Androids lydstyrkepanel (træk i bjælken), fra hurtigindstillingerne og inde i en app, der har sin egen lydstyrkeknap.

**Forventet:** Lydstyrken sænkes til grænsen.

### T4 – Slukket skærm
1. Start afspilning af lyd (fx musik eller en YouTube Premium/baggrundsafspilning, alternativt en lydbog).
2. Sluk skærmen med tænd/sluk-knappen, og vent 1 minut.
3. Tryk **lydstyrke op** flere gange med skærmen slukket.

**Forventet:** Lyden stiger ikke hørbart over grænsen. Tjek *Diagnostik* bagefter: sænkningerne er registreret.
*Bemærk:* Mange tablets ignorerer lydstyrkeknapperne, når skærmen er slukket og intet afspilles. Det er Androids opførsel.

### T5 – Efter genstart
1. Genstart tabletten, og lås den op.
2. Åbn **ikke** appen. Start en video, og skru op.

**Forventet:** Lyden sænkes til grænsen. Notifikationen "Lydbegrænsning er aktiv" er synlig. *Diagnostik › Seneste genstart*
viser "overvågning startet".
*Bemærk:* Overvågningen starter først, når tabletten er låst op første gang efter genstart (Androids `BOOT_COMPLETED`).

### T6 – Efter længere tids inaktivitet
1. Lad tabletten ligge urørt med slukket skærm i mindst 2 timer, gerne natten over og uden opladning.
2. Tænd skærmen, start en video, og skru op.

**Forventet:** Lyden sænkes til grænsen. *Diagnostik › Overvågning* viser "Kører".

Med adb kan dyb dvale (Doze) og app-standby fremtvinges i stedet for at vente:

```bash
adb shell dumpsys deviceidle force-idle
adb shell am set-inactive dk.kidsvolume.limiter true
# … tænd skærmen, afspil lyd, skru op, og kontrollér loggen …
adb shell dumpsys deviceidle unforce
adb shell am set-inactive dk.kidsvolume.limiter false
```

### T7 – Kablede hovedtelefoner (eller USB-C-hovedtelefoner)
1. Sæt grænsen til 40 %, og sørg for, at højttalerne står på grænsen.
2. Tilslut hovedtelefoner, og skru op til fx 70 %.
3. Tag hovedtelefonerne ud.

**Forventet:** Med standardindstillingen sænkes lydstyrken i hovedtelefonerne **ikke**. Forsiden viser
"Lyden går lige nu til hovedtelefoner, som ikke begrænses", og grænse-kortet hedder "Maks. lydstyrke · Hovedtelefoner"
med knappen *Sæt grænse for hovedtelefoner*. Når de tages ud, spiller højttalerne stadig højst 40 %.
Sæt hovedtelefonerne i igen: Deres lydstyrke er uændret 70 %.

### T8 – Bluetooth
Gentag T7 med Bluetooth-hovedtelefoner eller en Bluetooth-højttaler.

**Forventet:** Som T7, men kortet hedder "Maks. lydstyrke · Bluetooth".

### T9 – Egen grænse for hovedtelefoner og Bluetooth
1. Tilslut hovedtelefoner, og tryk *Sæt grænse for hovedtelefoner* på forsiden. Indtast PIN, og sæt grænsen til 30 %.
2. Skru op med knapperne.
3. Tag hovedtelefonerne ud, og skru op på højttalerne.
4. Gentag med Bluetooth og en grænse på fx 50 %. Prøv også Bluetooth-enhedens egne lydstyrkeknapper.
5. Slå grænsen for hovedtelefoner fra under *Indstillinger › Grænser pr. lydudgang*, og skru op i hovedtelefonerne.

**Forventet:** Hovedtelefonerne holdes på højst 30 %, Bluetooth på højst 50 %, og højttalerne beholder deres egen grænse (40 %).
Notifikationen viser alle tre, fx "Højttalere 40 % · Hovedtelefoner 27 % · Bluetooth 47 %". Tallene er rundet ned til enhedens trin.
Når grænsen er slået fra, røres hovedtelefonernes lydstyrke ikke længere. Notér, om Bluetooth-enhedens egne knapper begrænses
(det kræver "absolut lydstyrke", se [BEGRAENSNINGER.md](BEGRAENSNINGER.md)).

### T10 – PIN-beskyttelse
1. Lås appen (knappen *Lås nu*, eller luk appen og åbn den igen).
2. Forsøg at flytte slideren, åbne *Indstillinger* og slå beskyttelsen fra.
3. Indtast en forkert PIN fem gange.
4. Indtast den rigtige PIN.

**Forventet:** Slideren er låst, og indstillinger kræver PIN. Efter fem forkerte forsøg spærres tastaturet i 30 sekunder.
Den rigtige PIN låser op i 3 minutter, og indstillingerne låses igen, når appen forlades.
Beskyttelsen kan slås **til** uden PIN, men ikke fra.

### T11 – Forsinkelse
Mål tiden fra overskridelse til automatisk sænkning:

- **Selvtesten** (*Indstillinger › Test › Kør selvtest*) skruer selv ét trin over grænsen og måler, hvor lang tid der går,
  før appen har sænket lyden igen. Notér "Målt forsinkelse" og udløseren. Kør den 5 gange.
- **Med de fysiske knapper (præcist):** Kør i to terminaler:
  ```bash
  adb shell getevent -lt
  adb logcat -v monotonic -s VolumeLimiter
  ```
  Tidsstemplet for `KEY_VOLUMEUP … DOWN` i `getevent` og tidsstemplet for "Sænkede lydstyrken" i loggen er begge
  sekunder siden opstart. Forskellen er den samlede forsinkelse fra knaptryk til sænkning.
- **Uden computer:** Optag skærmen (Android-skærmoptagelse), mens der skrues op, og tæl billeder fra bjælken går over grænsen, til den falder tilbage.

**Forventet:** Normalt under 0,2 sekunder (udløser "knap/lydstyrke"). Hvis udløseren er "systemindstilling",
er den hurtige hændelse ikke kommet, og forsinkelsen er typisk 0,5–1 sekund. Notér det for den pågældende tablet.

### T12 – Robusthed
- Stryg appen væk fra listen over seneste apps. Skru op. **Forventet:** Lyden sænkes stadig.
- Tryk **Stop** ved appen i Androids liste over aktive apps (notifikationspanelet, Android 13+). **Forventet:** Overvågningen stopper.
  Er appen undtaget fra batterioptimering, genstarter vagthunden den inden for ca. 15 minutter, ellers når appen åbnes igen. Notér tiden.
- *Indstillinger › Apps › Kids Volume Limiter › Tving til at standse*. **Forventet:** Overvågningen er stoppet, indtil appen åbnes igen. Det er Androids regel.

## Situationer, hvor Android forhindrer appen i at reagere

Notér, om nogle af disse ses på tabletterne:

| Situation | Hvad der sker |
|---|---|
| Appen er tvangsstoppet | Android leverer ingen hændelser og starter ikke appen ved genstart, før den åbnes manuelt. |
| Stoppet via "Aktive apps" (Android 13+) | Tjenesten stopper. Vagthunden genstarter den, hvis appen er undtaget fra batterioptimering. |
| Producentens aggressive batterispare (fx Samsung "dyb dvale") | Tjenesten kan blive lukket. Følg rådene under *Baggrundsdrift*. |
| Før første oplåsning efter genstart | `BOOT_COMPLETED` kommer først efter oplåsning, så der er ingen overvågning før det. |
| Lydudgang med fast lydstyrke (fx visse USB-/HDMI-udgange) | Android tillader ikke ændring. Appen viser det på forsiden. |
| Apps, der selv skruer lyden ned i deres egen afspiller | Påvirker ikke systemets lydstyrke. Appen begrænser kun systemets medielydstyrke. |

## Resultatskema

| Test | Samsung Galaxy Tab A11 | Lenovo (model: ______) |
|---|---|---|
| Android-version / lydstyrketrin | | |
| T1 Fysiske knapper | | |
| T2 Andre apps | | |
| T3 Andre lydstyrkekilder | | |
| T4 Slukket skærm | | |
| T5 Efter genstart | | |
| T6 Lang inaktivitet | | |
| T7 Kablede hovedtelefoner | | |
| T8 Bluetooth | | |
| T9 Egen grænse for hovedtelefoner/Bluetooth | | |
| T10 PIN | | |
| T11 Forsinkelse (selvtest, 5 målinger) | | |
| T11 Forsinkelse (knap, getevent/video) | | |
| T11 Udløser (knap/lydstyrke eller systemindstilling) | | |
| T12 Robusthed | | |
| Afvigelser / bemærkninger | | |
