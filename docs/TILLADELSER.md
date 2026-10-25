# Tilladelser

Kids Volume Limiter bruger kun almindelige, offentlige Android-tilladelser. Der bruges ingen root,
systemrettigheder, skjulte API'er, tilgængelighedstjeneste eller enhedsadministrator.

## Tilladelser i appens manifest

| Tilladelse | Type | Hvorfor |
|---|---|---|
| `FOREGROUND_SERVICE` | Normal (gives automatisk) | Kører overvågningen som forgrundstjeneste, så den fortsætter, når appen er lukket. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Normal (gives automatisk) | Android 14+ kræver en tjenestetype. Ingen af de faste typer (fx `mediaPlayback`) passer til lydstyrkebegrænsning, så der bruges `specialUse` med en begrundelse i manifestet. |
| `POST_NOTIFICATIONS` | Runtime (Android 13+) – spørges i opsætningen | Viser den faste notifikation "Lydbegrænsning er aktiv". Tjenesten kører også uden, men så kan notifikationen ikke ses i notifikationspanelet. |
| `RECEIVE_BOOT_COMPLETED` | Normal | Starter overvågningen igen efter genstart af tabletten. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Normal – forælderen bekræfter i Androids egen dialog | Lader forælderen undtage appen fra batterioptimering. Det gør baggrundsdriften mere pålidelig og tillader vagthunden at genstarte tjenesten fra baggrunden. |
| `INTERNET` | Normal | Kræves af Capacitors WebView-ramme. Appen sender eller henter **ingen** data. Brugerfladen indlæses lokalt fra APK'en. |

## Tilladelser tilføjet af biblioteker

| Tilladelse | Fra | Hvorfor |
|---|---|---|
| `WAKE_LOCK` | AndroidX WorkManager | WorkManager holder kortvarigt CPU'en vågen, mens et job kører. Appen kører højst ét kort vagthund-job hvert 15. minut. |
| `ACCESS_NETWORK_STATE` | AndroidX WorkManager | Bruges kun til WorkManagers netværksbetingelser. Appens job har ingen. |
| `dk.kidsvolume.limiter.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX Core | Intern signaturtilladelse, der sikrer, at andre apps ikke kan sende til appens interne broadcast-modtagere. |

## Det, appen ikke har brug for

- **Ingen tilladelse til at ændre lydstyrken.** `AudioManager.setStreamVolume(STREAM_MUSIC, …)` kræver ingen særlig tilladelse.
- **Ingen adgang til "Forstyr ikke"** (`ACCESS_NOTIFICATION_POLICY`). Appen sænker kun lydstyrken og slår den aldrig helt fra, så det er ikke nødvendigt.
- **Ingen tilgængelighedstjeneste.** Den kunne blokere lydstyrkeknapperne helt, men kræver omfattende rettigheder, er begrænset af Google og kræver ekstra trin ved direkte installation ("Begrænsede indstillinger").
- **Ingen enhedsadministrator.** Den kunne forhindre afinstallation, men gør appen langt mere indgribende.

## Komponenter i manifestet

| Komponent | Eksporteret | Formål |
|---|---|---|
| `MainActivity` | Ja (app-ikonet) | Brugerfladen |
| `service.VolumeLimiterService` | Nej | Forgrundstjeneste (`foregroundServiceType="specialUse"`) |
| `service.BootReceiver` | Nej | Modtager `BOOT_COMPLETED` og `MY_PACKAGE_REPLACED` fra systemet |
| WorkManager-komponenter | Fra biblioteket | Kører vagthunden |
