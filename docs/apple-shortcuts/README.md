# Drive Time Notifier mit Apple Kurzbefehlen

[English](README_EN.md)

Diese Anleitung beschreibt einen nativen Apple-Kurzbefehl für iPhone und iPad: Start, Ziel und Ankunftszeit wählen, eine Autofahrt beim gewählten Routingdienst berechnen, festen und dynamischen Puffer ergänzen und einen Fahrttermin mit Erinnerung speichern. Es werden weder eine eigene App noch eine Website oder ein Apple-Entwicklerkonto benötigt. Apples integrierte Apps Kurzbefehle und Kalender genügen.

## Verfügbarkeit und Import

**Stand: Bauanleitung und herunterladbare Beispielkonfiguration. Es gibt noch keinen signierten, auf einem Apple-Gerät geprüften `.shortcut`-Download.** Die JSON-Datei ist eine Konfiguration, kein importierbarer Kurzbefehl. Die unten beschriebenen Aktionen müssen einmal im Kurzbefehle-Editor angelegt werden. Die Anleitung und Anbieter-Anfragen wurden anhand der offiziellen Dokumentation geprüft; ein Ausführungstest auf iOS steht aus.

Ein anschließend auf einem iPhone, iPad oder Mac erstellter Kurzbefehl kann über „Teilen“ als iCloud-Link oder als Datei für „Alle“ verteilt werden. Diese Freigabe validiert den Kurzbefehl über Apple. Empfänger können ihn danach importieren; sie benötigen keine Entwicklerkonten. Vor der Freigabe sämtliche persönlichen Startorte, API-Schlüssel und Kalenderauswahlen entfernen. Ein neuer Nutzer richtet diese Werte nach dem Import selbst ein.

Extern erzeugte Kurzbefehldateien benötigen eine Apple-Signierung. Auf einem Mac lässt sich eine vorhandene, gültige Quelldatei beispielsweise so signieren:

```sh
shortcuts sign --mode anyone --input Drive-Time-Notifier.unsigned.shortcut --output Drive-Time-Notifier.shortcut
```

Dieser Befehl erzeugt keine Aktionen aus der JSON-Konfiguration. Er signiert ausschließlich einen bereits gebauten Kurzbefehl. Die notwendigen Apple-Werkzeuge stehen in der Linux-Buildumgebung dieses Projekts nicht zur Verfügung. Es wird deshalb kein funktionsloser oder unsignierter Download als fertige Automation angeboten.

## Einrichtung

1. Einen eigenen Kalender „Fahrtzeiten“ anlegen oder einen vorhandenen Zielkalender wählen.
2. [config.example.json](config.example.json) herunterladen, lokal als `config.json` speichern und die gewünschten Werte eintragen. Die Datei beispielsweise unter `iCloud Drive/Shortcuts/DriveTimeNotifier/config.json` ablegen. Sie kann alternativ lokal in „Auf meinem iPhone“ liegen. Die Aktion zum Laden der Datei muss diesen Speicherort ausdrücklich auswählen.
3. In Kurzbefehle „Drive Time Notifier – Route“ anlegen. Am Anfang „Datei“ beziehungsweise „Datei aus Ordner abrufen“ mit der festen `config.json` auswählen, den Inhalt als Text lesen und mit „Wörterbuch aus Eingabe abrufen“ verarbeiten. Fehlende Dateien nicht durch einen Download aus dem Internet ersetzen.
4. Einstellungen mit „Wörterbuchwert abrufen“ auslesen und gemäß folgender Tabelle prüfen.
5. Die nachfolgend beschriebenen Aktionen ergänzen. Die englischen Suchbegriffe helfen, falls deutsche Aktionsnamen zwischen iOS-Versionen abweichen.
6. Beim ersten Lauf den Zugriff auf den Zielkalender, gegebenenfalls Standort sowie die gewählte Routingdomain erlauben. In „Neues Ereignis hinzufügen“ den tatsächlichen Zielkalender direkt auswählen; der Kalendername in einem Textfeld ersetzt diese Auswahl nicht.

| Einstellung | Gültige Werte |
| --- | --- |
| `provider` | `osrm`, `google`, `tomtom`, `here` |
| `osrm_base_url` | HTTPS-Basisadresse einer OSRM-Instanz, ohne `/route/v1/...` |
| Anbieter-Schlüssel | Nur der Schlüssel des ausgewählten Dienstes muss gesetzt sein |
| `fixed_buffer_minutes` | Ganze Zahl von 0 bis 180 |
| `dynamic_buffer_enabled` | `true` oder `false` |
| `dynamic_buffer_level` | `low`, `balanced`, `cautious` |
| `reminder_lead_minutes` | Ganze Zahl von 0 bis 180 |
| Standardstart | Beide Koordinaten setzen oder beide leer lassen |
| `show_confirmation` | Für manuelle Nutzung `true`; tägliche unbeaufsichtigte Verarbeitung kann `false` verwenden |

Für Google die Routes API aktivieren und deren Abrechnung einrichten; TomTom und HERE benötigen eigene Anbieterzugänge. Gebühren und Kontingente richten sich nach dem jeweiligen Tarif. Schlüssel nie in öffentliche Repositories oder in einen weitergegebenen Kurzbefehl eintragen. Die Konfigurationsdatei ist kein verschlüsselter Schlüsselspeicher; bei iCloud-Speicherung synchronisiert sie mit dem eigenen Apple-Konto.

OSRM nutzt offene Straßendaten und benötigt auf der voreingestellten Demo-Instanz keinen Schlüssel. Der öffentliche Demo-Server ist kein Dienst mit zugesicherter Verfügbarkeit. Für regelmäßige Nutzung einen geeigneten HTTPS-OSRM-Server konfigurieren. OSRM berücksichtigt in dieser Variante keinen Live-Verkehr.

## Aktionen: Eingabe und Koordinaten

1. In den Details „Im Share-Sheet anzeigen“ einschalten und die Eingabetypen Text, Orte und URLs aktivieren. Der Kurzbefehl kann auch direkt oder per Siri gestartet werden.
2. Wenn die Kurzbefehl-Eingabe einen verwendbaren Ort enthält, diesen mit „Orte aus Eingabe abrufen“ (`Get Locations from Input`) übernehmen. Sonst mit „Nach Eingabe fragen“ eine Zieladresse abfragen. Einen Karten-Kurzlink ohne erkennbaren Ort nicht als Adresse an den Routingdienst senden; stattdessen die Adresse abfragen.
3. Start über „Aus Menü auswählen“ wählen: Standardstart, aktueller Standort oder manuelle Adresse. Standardstart aus den beiden Konfigurationskoordinaten erzeugen; beim aktuellen Standort „Aktuellen Standort abrufen“ verwenden. Ohne gültigen Standardstart diese Menüoption weglassen.
4. Ankunft mit „Nach Eingabe fragen“, Eingabetyp Datum und Uhrzeit, abfragen. Für die tägliche Verarbeitung kommt dieser Wert direkt aus dem Kalendertermin.
5. Mit „Details von Orten abrufen“ (`Get Details of Locations`) Breitengrad und Längengrad für beide Orte auslesen. Apple übernimmt damit die Adressauflösung; der konfigurierte Dienst übernimmt die Routenberechnung. Keine zusätzliche kostenpflichtige Geocoding-API voraussetzen.
6. Koordinaten prüfen: Breitengrad −90 bis 90, Längengrad −180 bis 180. Für URLs Zahlen mit Dezimalpunkt und ohne Tausendertrennzeichen formatieren. Ankunft muss in der Zukunft liegen.
7. Eine „Wenn“-Verzweigung für den gewählten Anbieter erstellen. Jede Verzweigung setzt am Ende dieselben Zahlenvariablen: `duration_seconds`, `baseline_seconds`, `traffic_aware`.

## Routing-Anfragen

Bei allen Antworten zunächst die Fehlerfelder und das Vorhandensein mindestens einer Route prüfen. „Wörterbuchwert abrufen“ liest Objekte; „Element aus Liste abrufen“ wählt das **erste Element**. Die nachstehenden Indizes `[0]` beschreiben JSON, während der Kurzbefehle-Editor das erste Element als Position **1** bezeichnet.

### OSRM, offen

Mit „URL“ und „Inhalte von URL abrufen“, Methode GET:

```text
{osrm_base_url}/route/v1/driving/{start_lon},{start_lat};{ziel_lon},{ziel_lat}?overview=false&steps=false&alternatives=false
```

Nur `code=Ok` akzeptieren. `routes` → erstes Element → `duration` liefert Sekunden. `baseline_seconds = duration_seconds`, `traffic_aware = false`. Die Reihenfolge ist **Längengrad, Breitengrad**.

### Google Routes

POST `https://routes.googleapis.com/directions/v2:computeRoutes`

Header:

```text
Content-Type: application/json
X-Goog-Api-Key: {google_api_key}
X-Goog-FieldMask: routes.duration,routes.staticDuration
```

In „Inhalte von URL abrufen“ Anfragetext **JSON** wählen. Ein verschachteltes Wörterbuch verwenden, keinen als Zeichenkette serialisierten JSON-Text:

```json
{
  "origin": {"location": {"latLng": {"latitude": 52.52, "longitude": 13.405}}},
  "destination": {"location": {"latLng": {"latitude": 52.50, "longitude": 13.40}}},
  "travelMode": "DRIVE",
  "routingPreference": "TRAFFIC_AWARE",
  "computeAlternativeRoutes": false
}
```

Die vier Beispielzahlen durch die Koordinatenvariablen ersetzen. `routes` → erstes Element → `duration` und `staticDuration` lesen. Diese Werte sind Zeichenketten wie `"165s"`: das abschließende `s` entfernen und als Dezimalzahl interpretieren. Beide Werte sind **Sekunden**. `traffic_aware = true`.

### TomTom

GET, Schlüssel und andere Parameter URL-kodieren:

```text
https://api.tomtom.com/routing/1/calculateRoute/{start_lat},{start_lon}:{ziel_lat},{ziel_lon}/json?key={tomtom_api_key}&travelMode=car&traffic=true&departAt=now&routeType=fastest&computeTravelTimeFor=all&maxAlternatives=0
```

`routes` → erstes Element → `summary` lesen. `travelTimeInSeconds` ist die Fahrtdauer, `noTrafficTravelTimeInSeconds` die Basisdauer. Fehlt die Basisdauer, die Fahrtdauer als Basis verwenden. `traffic_aware = true`. Reihenfolge: **Breitengrad, Längengrad**.

### HERE Routing v8

GET:

```text
https://router.hereapi.com/v8/routes?transportMode=car&routingMode=fast&origin={start_lat},{start_lon}&destination={ziel_lat},{ziel_lon}&return=summary&departureTime=now&apikey={here_api_key}
```

`routes` → erstes Element → `sections` lesen. Mit „Mit jedem wiederholen“ die `summary.duration` **aller Abschnitte** summieren. Ebenso `summary.baseDuration` summieren; fehlt sie in einem Abschnitt, dessen `duration` verwenden. Ergebnis sind Sekunden. `traffic_aware = true`. Nicht nur den ersten Abschnitt verwenden, etwa bei Fähren.

### Fehler und Planung für spätere Termine

Bei Netzfehler, fehlendem Schlüssel, abgewiesener API-Anfrage, fehlenden Koordinaten, leerer Route oder ungültiger Dauer eine Meldung ausgeben und „Diesen Kurzbefehl stoppen“. **Keinen Null-Minuten-Termin als Ersatz speichern.** Eine Meldung darf Anbieter und Fehlercode enthalten, aber keine vollständige URL mit API-Schlüssel.

Die einfachen Anfragen oben rechnen mit der Verkehrsschätzung beim Ausführen. Sie garantieren keine Verkehrsprognose für einen beliebigen späteren Ankunftstermin. Für einen neu gebauten Kurzbefehl ist das ein bewusst einfacher erster Umfang. Bei täglicher Planung am Vorabend kann man die Berechnung später erneut vor der Fahrt starten. Dabei denselben Fahrttermin aktualisieren, nicht einen weiteren Termin anlegen.

## Fester und dynamischer Puffer

Die Berechnung entspricht `DynamicArrivalBuffer.kt` der Android-App. Rohwerte verwenden, nicht eine bereits auf volle Minuten gerundete Fahrtzeit:

```text
m = duration_seconds / 60
traffic_delay_seconds = max(0, duration_seconds - baseline_seconds)
```

Wenn dynamischer Puffer aus ist: `extra = 0`. Andernfalls mit „Wenn“-Aktionen die passende Basis wählen:

| Level | Fahrtzeit m | Basis in Minuten |
| --- | --- | --- |
| low | <30 / <60 / <120 / <180 / sonst | 0 / 2 / 4 / 7 / 10 |
| balanced | <20 / <45 / <90 / <150 / <240 / sonst | 0 / 3 / 6 / 10 / 15 / 20 |
| cautious | <20 / <45 / <90 / <150 / <240 / sonst | 2 / 5 / 10 / 15 / 22 / 30 |

Stufen nacheinander als Wenn/Sonst-Wenn prüfen. Obergrenzen sind **exklusiv**: Genau 45 Minuten liegen bei `balanced` bereits in der 6-Minuten-Stufe.

Bei OSRM eine Unsicherheitsreserve von `min(10, aufrunden(m × 0.03))` ergänzen. Bei Google, TomTom und HERE nur `min(5, aufrunden(traffic_delay_seconds / 60 × 0.10))` ergänzen. „Zahl runden“, Modus immer aufrunden, Stellenzahl 0 verwenden. Bereits berücksichtigte Verkehrsverzögerungen nicht nochmals vollständig addieren.

```text
extra = min(45, basis + unsicherheitsreserve)
total_buffer = min(180, fixed_buffer_minutes + extra)
route_minutes = aufrunden(duration_seconds / 60)
abfahrt = ankunft - route_minutes Minuten - total_buffer Minuten
```

`extra` ist bei deaktiviertem dynamischem Puffer immer 0. Negative oder nichtnumerische Werte bereits bei der Einrichtung zurückweisen. Zum Subtrahieren „Datum anpassen“ (`Adjust Date`) verwenden, damit Zeitzonen und Datumswechsel korrekt bleiben. Liegt die berechnete Abfahrt in der Vergangenheit, bei manueller Planung eine Warnung mit Bestätigung anzeigen; die unbeaufsichtigte Automation lässt den Termin aus.

Beispiel: 60 Minuten OSRM-Fahrt, 10 Minuten fester Puffer, Stufe `balanced`: Basis 6 plus Unsicherheit 2 ergibt dynamisch 8 Minuten. Bei Ankunft 10:00 liegt die Abfahrt bei 08:42.

## Kalendereintrag und Benachrichtigung

1. Eine Notizkennung erzeugen, zum Beispiel `DTN-IOS|{Ankunft als ISO-Datum}|{Startkoordinaten}|{Zielkoordinaten}`. Anbieter, berechnete Abfahrt und Puffer gehören nicht in diese Kennung, damit eine Neuberechnung denselben Fahrttermin findet.
2. Mit „Kalenderereignisse suchen“ im tatsächlich gewählten Zielkalender suchen, Datum zwischen Ankunft minus einem Tag und Ankunft plus einem Tag, Notizen enthalten die Kennung. Einen exakten Kennungsvergleich ergänzen. Wenn ein Eintrag gefunden wird, mit „Kalenderereignis bearbeiten“ aktualisieren. Bei mehreren Treffern abbrechen und eine Duplikatmeldung anzeigen.
3. Sonst „Neues Ereignis hinzufügen“ (`Add New Event`) verwenden. Titel: `Fahrt zum Ziel`. Start: Abfahrt. Ende: Ankunft. Ort: Zieladresse. Ganztägig: aus. Der Termin blockiert Fahrtzeit **einschließlich** der gesamten Ankunftsreserve.
4. Notizen enthalten Kennung, Start und Ziel, Routinganbieter sowie `Fahrt: … Min; fester Puffer: … Min; dynamischer Puffer: … Min`.
5. In den erweiterten Feldern der Kalenderaktion die Erinnerung aktivieren. Für 0 Minuten „Bei Ereignisbeginn“, für andere unterstützte Werte „… Minuten vorher“ wählen. Falls eine iOS-Version dieses Feld nicht per Zahlenvariable setzen kann, den gewünschten Vorlauf einmal direkt in der Aktion auswählen. „Benachrichtigung anzeigen“ im Kurzbefehl wäre nur eine sofortige Meldung und ersetzt diese Kalendererinnerung nicht.
6. Bei `show_confirmation=true` vor dem Schreiben Abfahrt, Ankunft, Fahrtzeit und beide Puffer anzeigen und bestätigen lassen. Bei `false` direkt schreiben, aber alle Fehlerprüfungen beibehalten.
7. Benachrichtigungen für Kalender in iOS zulassen; Fokus und Systemeinstellungen können deren Anzeige beeinflussen.

## Optional: tägliche Automation

Zusätzlich „Drive Time Notifier – Morgen“ anlegen. Mit „Datum anpassen“ morgen 00:00 und übermorgen 00:00 bestimmen; nicht einfach 24 Stunden addieren. „Kalenderereignisse suchen“ in den vom Nutzer gewählten Quellkalendern, Beginn in diesem Zeitraum, nicht ganztägig, aufsteigend sortieren. Den Fahrtzeiten-Zielkalender ausschließen.

Für jeden Termin mit Ort den Routen-Kurzbefehl über „Kurzbefehl ausführen“ mit einem Wörterbuch aufrufen: `destination`, `arrival`, `origin_latitude`, `origin_longitude`, `title`. Im Routen-Kurzbefehl am Anfang prüfen, ob diese Wörterbuch-Eingabe vorliegt. In diesem Fall Orte und Datum daraus übernehmen und sämtliche interaktiven Abfragen überspringen. Für unbeaufsichtigte Nutzung einen festen Startort einsetzen, keine Standortabfrage. Keine Datei- oder Kalenderauswahl „Jedes Mal fragen“ verwenden. Termine ohne Ort auslassen und in der Abschlussmeldung zählen.

In Kurzbefehle eine persönliche Automation „Tageszeit“, beispielsweise täglich 20:00, erstellen und „Drive Time Notifier – Morgen“ ausführen. „Sofort ausführen“ wählen, sofern angeboten. Die persönliche Automation und Erstfreigaben richtet jeder Nutzer auf seinem eigenen Gerät ein. Ein importierter Routen-Kurzbefehl allein installiert keinen zeitgesteuerten Trigger und gewährt keine Berechtigungen.

## Prüfliste vor einem öffentlichen Shortcut-Download

- Auf iPhone oder iPad manuell ausführen und jeden Anbieter mit einem eigenen gültigen Testschlüssel prüfen.
- OSRM-Reihenfolge lon/lat, TomTom/HERE-Reihenfolge lat/lon und Google-Sekundenstrings prüfen.
- HERE-Testantwort mit zwei Abschnitten verwenden und Gesamtsumme prüfen.
- Dynamische Stufengrenzen, deaktivierten Puffer sowie Nacht- und Sommerzeitwechsel testen.
- Termin tatsächlich speichern und Kalendererinnerung auf dem Gerät prüfen.
- Erneut berechnen und genau einen aktualisierten Fahrttermin erhalten.
- Fehlende Schlüssel, leere Antworten und offline testen: kein falscher Kalendereintrag.
- Beispielkonfiguration und teilbaren Kurzbefehl von Geheimnissen und persönlichen Daten bereinigen.
- Erst danach als Datei für „Alle“ oder iCloud-Link exportieren und den Import auf einem zweiten Gerät prüfen.

## Offizielle Quellen

- [Apple: Kurzbefehl aus einer anderen App starten](https://support.apple.com/guide/shortcuts/apd163eb9f95/ios)
- [Apple: Kurzbefehle teilen](https://support.apple.com/guide/shortcuts/apdf01f8c054/ios)
- [Apple: Kurzbefehle über die Kommandozeile](https://support.apple.com/guide/shortcuts-mac/apd455c82f02/mac)
- [Apple: persönliche Automationen](https://support.apple.com/guide/shortcuts/apdfbdbd7123/ios)
- [OSRM Route API](https://project-osrm.org/docs/v5.24.0/api/#route-service)
- [Google Routes computeRoutes](https://developers.google.com/maps/documentation/routes/reference/rest/v2/TopLevel/computeRoutes)
- [TomTom Calculate Route v1](https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/calculate-route)
- [HERE Routing v8, Summary](https://docs.here.com/routing/docs/routing-v8-route-summary)
