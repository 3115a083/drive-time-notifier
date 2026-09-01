# Drive Time Notifier

Android-App, die Fahrzeiten zu Kalendereinträgen mit Verkehrslage berechnet und die Fahrt als eigenen Kalendereintrag oder als ICS-Datei anlegt.

## Status

Aktueller Stand: funktionsfähiger Android-MVP in Kotlin und Jetpack Compose.

Enthalten sind:

- manueller Start, Ziel und Terminzeit
- gespeicherter Standard-Startort
- Zieltermin aus dem Android-Kalender wählen
- vorherigen Kalendereintrag als temporären Start verwenden
- verkehrsabhängige Fahrzeit mit Google Routes API
- Karte mit Route auf OpenStreetMap/osmdroid
- Stauindikator aus Verkehrsdauer gegenüber statischer Fahrzeit
- optional OSM-Blitzerhinweise und Parkmöglichkeiten
- anpassbarer Ankunftspuffer
- automatische Kürzung des Puffers bei eng aufeinanderfolgenden Terminen
- Warnung bei voraussichtlich nicht erreichbaren Folgeterminen
- Fahrt als Eintrag in einen frei wählbaren Android-Kalender speichern
- frei wählbare Erinnerung vor Abfahrt
- alternativ ICS-Datei erzeugen
- automatische Verarbeitung der Termine des nächsten Tages, Standard 21:00 Uhr
- frei wählbare Quellkalender für die Automatik
- Tasker/Broadcast-Integration mit 256-Bit-Token
- App-Shortcut zum Auslösen der Verarbeitung des nächsten Tages
- Light/Dark Mode und Material-You-Farben
- Android-Keystore für API-Key und Automation-Token

## Architektur

Die App verwendet bewusst Android-Standard-APIs, um OEM-Kalender-Apps möglichst wenig Einfluss auf die Funktion zu geben.

- UI: Jetpack Compose, Material 3
- Kalender: Android `CalendarContract`
- Hintergrundjobs: WorkManager
- Einstellungen: DataStore
- Geheimnisse: Android Keystore, AES/GCM
- Routing: Google Routes API und Google Geocoding API
- Karte: osmdroid/OpenStreetMap
- optionale POIs: OpenStreetMap Overpass API
- HTTP: OkHttp
- Export: Android MediaStore und FileProvider

Die App legt Kalendereinträge direkt über den Android Calendar Provider an. Sie verwendet keine Hersteller-Intents von Samsung Calendar, Huawei Calendar oder anderen OEM-Apps. Das reduziert Unterschiede zwischen Kalender-Oberflächen. Die tatsächliche Synchronisation übernimmt weiterhin der auf dem Gerät konfigurierte Calendar Provider.

## Einrichtung

### Voraussetzungen

- Android Studio mit JDK 17
- Android SDK 35
- minSdk 26
- Google Cloud Projekt mit aktivierter Routes API und Geocoding API

Die App enthält keinen fest einkompilierten API-Key. Den Key trägt der Nutzer in den Einstellungen ein. Er wird lokal mit einem Schlüssel aus dem Android Keystore verschlüsselt.

Für Produktionsbetrieb sollte der Google-Key zusätzlich serverseitig auf die wirklich benötigten APIs und ein enges Kontingent beschränkt werden. Ein REST-API-Key in einer Client-App kann trotz Keystore nicht absolut gegen Extraktion auf einem kompromittierten Gerät geschützt werden.

### Build

```bash
gradle testDebugUnitTest assembleDebug
```

Alternativ das Projekt in Android Studio öffnen und das `app`-Modul starten.

GitHub Actions baut bei Push und Pull Request automatisch Unit-Tests und eine Debug-APK.

## Bedienung

### Manuelle Fahrt

1. Startadresse eingeben oder den gespeicherten Standard-Start verwenden.
2. Optional „Start aus Kalenderevent“ wählen. Das Ende dieses Termins wird dann als frühestmöglicher Fahrtbeginn berücksichtigt.
3. Ziel und Terminzeit manuell eintragen oder „Zieltermin“ wählen.
4. „Berechnen“ drücken.
5. Route, Entfernung, Verkehrsdauer, statische Dauer und Stauindikator prüfen.
6. Fahrt in den gewählten Kalender schreiben oder als ICS exportieren.

Der Titel eines Termins wird bei der manuellen Kalenderauswahl lokal angezeigt. Er wird nicht an Google oder Overpass übertragen.

### Pufferlogik

Der Puffer beschreibt, wie viele Minuten vor Beginn des Zieltermins die Ankunft geplant werden soll.

Wenn zwei Termine mit vollem Puffer nicht erreichbar sind, die direkte Fahrt aber noch möglich ist, verkürzt die App den Puffer automatisch und zeigt einen Hinweis.

Wenn die direkte Fahrt selbst nicht rechtzeitig möglich ist, zeigt die App eine deutliche Warnung. Die erzeugte Fahrt endet trotzdem zum Beginn des zweiten Termins. Dadurch kann sich der Fahrtbeginn mit dem vorherigen Termin überschneiden.

## Automatik

In den Einstellungen können Quellkalender, Zielkalender, Uhrzeit und automatischer Modus gewählt werden.

Standardzeit ist 21:00 Uhr.

Für den nächsten Tag:

1. Es werden nur Termine aus den gewählten Quellkalendern betrachtet.
2. Termine ohne Ort werden ignoriert.
3. Die erste Fahrt startet am gespeicherten Standard-Startort.
4. Jede weitere Fahrt startet am Ort des vorherigen Termins.
5. Ende des vorherigen Termins, Fahrtzeit und Puffer werden gegeneinander geprüft.
6. Fahrten werden in den Zielkalender geschrieben oder als ICS in `Download/DriveTimeNotifier` gespeichert.

WorkManager wird absichtlich verwendet, weil er Doze, App-Standby, Neustarts und viele OEM-Energiesparmechanismen besser behandelt als ein dauerhaft laufender Dienst. Android garantiert bei periodischer Hintergrundarbeit keine sekundengenaue Ausführung. Die konfigurierte Uhrzeit ist daher ein Zielzeitpunkt, kein Echtzeit-Alarm.

## Kalender und OEM-Kompatibilität

Die Kalenderimplementierung verwendet ausschließlich `CalendarContract.Calendars`, `CalendarContract.Instances`, `CalendarContract.Events` und `CalendarContract.Reminders`.

Das vermeidet Abhängigkeiten von der installierten Kalender-App.

Berücksichtigte Eigenheiten:

- Kalender werden über ihre stabile interne Calendar-ID ausgewählt.
- nur sichtbare Kalender werden angeboten
- wiederkehrende Termine werden über `Instances` gelesen
- Event-IDs werden zusammen mit Instanzzeiten dedupliziert
- Erinnerungen werden separat über `CalendarContract.Reminders` angelegt
- fehlgeschlagene Reminder-Erstellung verhindert nicht das Speichern des Events
- Zeitzone des Events wird explizit gesetzt
- Termine ohne Ort werden bei automatischer Verarbeitung übersprungen

Auf Geräten mit aggressiven Hersteller-Energiesparregeln kann der Nutzer die App gegebenenfalls von Akkuoptimierungen ausnehmen, falls die nächtliche WorkManager-Ausführung stark verzögert wird.

## Datenschutz

Die App hat keinen eigenen Server.

Lokal verarbeitet werden:

- Kalender-ID
- Datum und Uhrzeit
- Ort
- Ende des vorherigen Termins
- Titel ausschließlich zur manuellen Auswahl in der UI

Nicht an Routing- oder POI-Dienste gesendet werden:

- Termintitel
- Terminbeschreibung
- Kalendername
- Konto-/E-Mail-Adresse des Kalenders

An Google werden für eine Berechnung gesendet:

- Startadresse
- Zieladresse
- gewünschte Ankunftszeit
- daraus geocodierte Start- und Zielkoordinaten

Wenn Blitzer oder Parkplätze aktiviert sind, wird ein geografischer Routenausschnitt an die Overpass API gesendet. Diese Abfrage ist standardmäßig deaktiviert.

## Blitzer und Parkplätze

Die Optionen sind standardmäßig aus.

Blitzer stammen aus OpenStreetMap-Einträgen mit `highway=speed_camera`. Diese Daten können unvollständig, veraltet oder regional rechtlich eingeschränkt sein. Die App behandelt sie deshalb nur als Hinweis und niemals als verlässliche Warnquelle.

Parkplätze stammen aus OpenStreetMap-Einträgen mit `amenity=parking` im Routenausschnitt. Eine Belegungs- oder Verfügbarkeitsgarantie gibt es nicht.

## Tasker-Integration

Externe Broadcasts werden nur verarbeitet, wenn das in den Einstellungen angezeigte 256-Bit-Token als Extra `token` mitgesendet wird. Das Token wird verschlüsselt mit Android Keystore gespeichert.

### Nächsten Tag verarbeiten

Action:

```text
de.drivetime.notifier.ACTION_PROCESS_NEXT_DAY
```

Package:

```text
de.drivetime.notifier
```

Extra:

```text
token=<TOKEN_AUS_DEN_EINSTELLUNGEN>
```

### Einzelnen Termin direkt verarbeiten

Action:

```text
de.drivetime.notifier.ACTION_PROCESS_EVENT
```

Extras:

```text
token=<TOKEN>
mode=process
origin=Startadresse
destination=Zieladresse
arrival_millis=Unixzeit_in_Millisekunden
previous_end_millis=optional
```

Wenn `origin` leer ist, verwendet die App den Standard-Startort.

### App vorausgefüllt öffnen

Gleiche Action, aber:

```text
mode=open
origin=Startadresse
destination=Zieladresse
datetime=2026-09-01 14:30
previous_end_millis=optional
```

Android kann das Starten einer Activity aus dem Hintergrund je nach Version und OEM einschränken. Für vollständig unbeaufsichtigte Abläufe sollte deshalb `mode=process` verwendet werden.

## Samsung Routines und App-Aktion

Die App stellt einen statischen Launcher-Shortcut „Fahrten morgen“ bereit. Launcher oder Automatisierungs-Apps, die Android-App-Shortcuts ausführen können, können damit die Verarbeitung des nächsten Tages starten.

Für Automatisierungsprogramme mit Broadcast-Unterstützung ist die token-geschützte Tasker-Schnittstelle robuster.

## Sicherheit

Umgesetzte Maßnahmen:

- kein API-Key im Quellcode oder Manifest
- API-Key verschlüsselt mit AES/GCM und Android Keystore
- Automation-Token mit 256 Bit Zufall, ebenfalls Keystore-verschlüsselt
- konstanter Zeitvergleich des Tokens
- keine Klartext-Netzwerkverbindungen, `usesCleartextTraffic=false`
- kein Android-Backup der App-Daten, `allowBackup=false`
- FileProvider nicht exportiert
- Kalenderzugriff nur über Runtime-Permissions
- keine Termintitel oder Beschreibungen in Netzwerkrequests
- HTTP-Timeouts für Routing- und Overpass-Anfragen
- keine dauerhaft laufenden Hintergrunddienste
- GitHub Actions mit minimalen `contents: read`-Rechten
- keine Geheimnisse im Repository

Ein Android-Gerät mit Root, kompromittiertem Betriebssystem oder aktivem Debugging gegen eine manipulierte App kann Client-Geheimnisse grundsätzlich kompromittieren. Das lässt sich bei einer reinen Client-App nicht vollständig verhindern.

## Ressourcenverbrauch

Die App vermeidet Polling und dauerhafte Services.

- automatische Verarbeitung: einmal täglich über WorkManager
- Netzwerk nur bei Routenberechnung oder aktivierten POI-Optionen
- POI-Abfragen standardmäßig aus
- Kalenderabfragen auf konkrete Zeitfenster begrenzt
- Karte nur sichtbar, wenn bereits eine Route berechnet wurde

## Tests

`DrivePlannerTest` prüft:

- vollen Puffer
- automatisch verkürzten Puffer
- nicht rechtzeitig erreichbare Folgetermine

Die CI führt Unit-Tests und `assembleDebug` aus.

## Bekannte Grenzen

- Verkehrsqualität hängt von Google Routes ab.
- Google Routes und Geocoding benötigen einen vom Nutzer bereitgestellten API-Key.
- OSM-Blitzer und Parkplätze sind Community-Daten und nicht garantiert vollständig.
- Die automatische Uhrzeit über WorkManager ist nicht sekundengenau.
- Ein Zielkalender muss Schreibzugriff erlauben. Schreibt ein Konto nur lesbar, schlägt der Insert sauber fehl.
- Die App nimmt keine Änderungen am Originaltermin vor.
- Ein einzelner REST-API-Key kann auf einem kompromittierten Client nie vollständig geheim gehalten werden.

## Lizenz

Noch keine Lizenzdatei hinterlegt. Vor Veröffentlichung sollte eine passende Open-Source-Lizenz gewählt werden.
