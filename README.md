# RB64 Live

Android-App für die RB64 Münster ↔ Enschede: Abfahrten in Echtzeit, eigene Prognose bei Zugkreuzungen,
die Strecke live, Mitfahren mit Fahrt-Auswertung und Widgets.

- **Webseite und Download:** https://simonroev.github.io/rb64-live/
- **Datenschutz:** https://simonroev.github.io/rb64-live/datenschutz.html

## Aufbau

| Ordner     | Inhalt |
|------------|--------|
| `android/` | Quellcode der App (Kotlin, Jetpack Compose) – Stand der jeweils veröffentlichten Version |
| `docs/`    | Webseite (GitHub Pages) mit der aktuellen APK unter `docs/download/` |

Entwickelt wird in einem privaten Repository zusammen mit dem Auswerte-Server. Hierher kommt je Version ein Stand
der App (Commit „Version x.y“, Tag `vx.y`).

## Selbst bauen

Die App nutzt Firebase (anonyme Anmeldung, Push, Firestore). Die Konfiguration `android/app/google-services.json`
ist nicht enthalten – zum Bauen braucht es ein eigenes Firebase-Projekt mit der App-ID `de.rb64.live`.
Ohne den Auswerte-Server bekommt die App keine Zugdaten.

```
cd android
./gradlew assembleDebug
```

## Prüfen der APK

Die APK auf der Webseite ist mit diesem Zertifikat signiert:

```
SHA-256: 65:A1:59:C0:C7:10:0B:2D:BC:12:2E:36:42:E5:51:5D:CC:26:66:5F:61:F8:A8:83:91:2A:9F:71:23:5B:4D:69
```

(`apksigner verify --print-certs rb64-live.apk`). Die Prüfsumme der Datei steht auf der Webseite.

## Lizenz

GNU General Public License v3.0 – siehe [`LICENSE`](LICENSE). Wer den Code weiterverwendet und weitergibt,
muss seine Fassung ebenfalls unter der GPL offenlegen.

RB64 Live ist ein privates Projekt und keine App der Deutschen Bahn, von eurobahn oder NS.
