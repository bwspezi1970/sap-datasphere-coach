# SAP Coach (Starter für den Claude-Sprachmodus)

Startet mit einem Befehl die Claude-App, öffnet das Projekt **SAP Datasphere** und schaltet den **Sprachmodus** ein. Läuft über das normale Claude-Abo – keine API, keine Zusatzkosten.

## Installation
1. Unter **Releases** die neueste `SAP-Coach.apk` auf dem Handy herunterladen und installieren.
2. Es erscheinen zwei Symbole: **SAP Coach** (Start) und **SAP Coach Setup** (Einrichtung).

## Einrichtung (SAP Coach Setup)
1. **Bedienungshilfe einschalten** (Einstellungen → Bedienungshilfen → Installierte Apps → SAP Coach).
   Ausgegraut? App-Info → ⋮ → „Eingeschränkte Einstellungen zulassen“.
2. **Ablauf anlernen**: Claude öffnet sich mit einer blauen Ebene. Nacheinander tippen: Menü → Projekte → SAP Datasphere → (neuer Chat) → Sprachmodus-Symbol → „Fertig“.
3. **Jetzt starten** zum Testen.
4. Optional: **Autostart** beim Verbinden mit dem Auto-Bluetooth.

## Im Auto
- Symbol **SAP Coach** antippen, oder „Hey Google, öffne SAP Coach“, oder Autostart.
- Ändert Claude seine Oberfläche, einfach neu anlernen.

Hinweis: `app/debug.keystore` ist ein reiner Debug-Schlüssel (Passwort `android`), damit Updates ohne Deinstallation funktionieren. Build über GitHub Actions bei jedem Push auf `main`.
