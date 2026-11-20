# SAP Datasphere Coach (Android Auto)

Private Android-App: Fragen zu SAP Datasphere per Sprache im Auto stellen, Claude antwortet auf Basis hinterlegter Quell-PDFs, die Antwort wird vorgelesen.

## Installation
1. Unter **Releases** die neueste `SAP-Coach.apk` auf dem Handy herunterladen und öffnen (Installation aus dieser Quelle zulassen).
2. Android Auto: Entwicklermodus aktivieren → Drei-Punkte-Menü → Entwicklereinstellungen → **Unbekannte Quellen** einschalten.
3. In Android Auto → „Launcher anpassen“ prüfen, dass **SAP Coach** eingeblendet ist.

## Einrichtung (in der App auf dem Handy)
1. Claude-API-Schlüssel von console.anthropic.com eintragen, **Speichern**.
2. **PDFs hinzufügen**: die vereinbarten Quelldokumente auswählen (werden in die Files API hochgeladen).
3. Testfrage stellen – die Antwort wird vorgelesen.

## Im Auto
- „Hey Google, spiele *Was ist ein Analytic Model* auf SAP Coach.“
- Oder SAP Coach in Android Auto öffnen und Mikrofon/Suche antippen.
- Menü: So funktioniert's · Letzte Antwort wiederholen · Neues Gespräch beginnen · frühere Antworten.

Der Gesprächsverlauf (letzte 30 Fragen) wird lokal gespeichert; die letzten 8 werden bei jeder Frage als Kontext mitgeschickt.

## Hinweise
- Kosten: Abrechnung über das Claude-API-Konto. Dokumente werden 1 Stunde zwischengespeichert (Prompt Caching), Folgefragen sind dadurch günstiger.
- Der Signaturschlüssel `app/debug.keystore` ist ein reiner Debug-Schlüssel (Passwort `android`), damit Updates ohne Deinstallation funktionieren.
- Build läuft automatisch über GitHub Actions bei jedem Push auf `main`.
