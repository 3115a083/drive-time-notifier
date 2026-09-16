# Drive Time Notifier – TODO

## Nächster Build

### In-App Update-Checker und Versionsanzeige

- In der App im Info-/Über-Bereich neben dem bestehenden GitHub-Symbol die aktuell installierte App-Version anzeigen, z. B. `Version 1.1.0`.
- Direkt daneben bzw. in derselben Zeile eine Aktion `Nach Updates suchen` mit geeignetem Update-/Refresh-Symbol anbieten.
- Die Prüfung soll direkt in der App erfolgen. Nutzer sollen GitHub nicht öffnen müssen, um festzustellen, ob eine neuere Version existiert.
- Als Updatequelle ausschließlich die offiziellen Releases des Projektrepositories verwenden.
- Lokale `BuildConfig.VERSION_NAME`/`versionCode` mit dem neuesten geeigneten Release vergleichen. Semantische Versionsnummern robust behandeln und Debug-/Prerelease-Versionen nicht fälschlich als reguläres Update anbieten.
- Ergebnis klar anzeigen: `App ist aktuell`, `Neue Version X verfügbar` oder eine verständliche Fehlermeldung bei fehlendem Netzwerk/Serverfehler.
- Bei verfügbarer neuer Version Release-Informationen und eine bewusste Aktion zum Öffnen/Herunterladen anbieten. Kein automatischer Download und keine stille Installation.
- Die normale App-Nutzung darf nicht von der Updateprüfung abhängen. Netzwerkfehler müssen folgenlos bleiben.
- Keine Telemetrie, Gerätekennung, Kalenderdaten, Standortdaten oder sonstige Nutzerdaten an die Updatequelle senden.
- HTTPS erzwingen, kurze Timeouts und begrenzte Antwortgröße verwenden. Nur erwartete GitHub-API-/Release-Hosts akzeptieren und externe/unerwartete Redirects ablehnen.
- Optional den Zeitpunkt der letzten erfolgreichen Prüfung lokal anzeigen.
- DE/EN-Texte, Light/Dark/System und bestehende Material-3-Darstellung berücksichtigen.
- Debug-Build: klar anzeigen, dass es sich um die Debug-Version handelt, und nicht versehentlich einen regulären Release als direkt installierbares Update über die Debug-App behandeln.
- Unit-Tests für Versionsvergleich, Prerelease-Behandlung, ungültige Versionsstrings, Netzwerkfehler und `keine neue Version` ergänzen.
- Vor Veröffentlichung mit `clean test lint assembleDebug assembleRelease` prüfen.
