# Fuß- und Radwege: nächste große Funktionsversion

Status: vorbereitete Spezifikation vom 20.11.2026, noch nicht implementiert. Version 1.4.0 bleibt die geprüfte Autofahrt-Funktionslinie. Die spätere Versionsnummer ist noch nicht festgelegt.

## Produktziel und Bedienung

- Manuelle Auswahl: Auto, Zu Fuß, Fahrrad, optional Automatisch. Standard nach Migration bleibt Auto.
- Die explizite Auswahl gilt für diese Planung und wird nicht durch Hintergrundresultate oder Entfernungsregeln überschrieben.
- Fuß und Rad erhalten jeweils einen eigenen festen Zeitpuffer in Minuten. Autopuffer und bestehende dynamische Autopuffer bleiben unverändert.
- Startzeit = gewünschte Ankunft minus tatsächliche Routendauer minus Puffer des gewählten Verkehrsmittels. Kein zusätzlicher Autopuffer für Fuß/Rad.
- Ergebnis, Historie, ICS, Kalendereintrag und externe Navigation zeigen Verkehrsmittel, Strecke, Dauer und angewendeten Puffer. Ein Verkehrsmittelwechsel berechnet die Route neu; keine Umrechnung einer Autostrecke anhand einer angenommenen Geschwindigkeit.
- Parkplatz- und Ladesäulensuche bleiben für Auto verfügbar. Sie starten bei reinen Fuß-/Radwegen nicht.
- Responsive Verkehrsmittelauswahl, barrierefreie Beschriftungen, Deutsch und Englisch. Neue Zahlenfelder erfüllen docs/INPUT_EDITING.md: ungültige/temporär leere Entwürfe erhalten, erst validierte Werte speichern.

## Optionale Automatik im Umfeld

Die Automatik ist standardmäßig ausgeschaltet. Fuß und Rad lassen sich getrennt einschalten, jeweils mit eigener maximaler Entfernung in Kilometern und eigenem Puffer. Der Nutzer wählt zusätzlich, ob die Regeln auch für die bestehende automatische Kalenderverarbeitung gelten. Keine zusätzliche periodische Aufgabe, keine Hintergrundortung.

Das Umfeld bezieht sich auf den aufgelösten Start der jeweiligen Planung, also den manuell gewählten, gespeicherten oder bereits über die Kalenderlogik bestimmten Startort. Der aktuelle Standort wird weiterhin nur auf ausdrückliche Anforderung abgefragt.

Vorgeschlagene, vor Implementierung im UI zu bestätigende Anfangswerte: Fußgrenze 1,5 km, Radgrenze 8 km, beide Puffer 5 Minuten. Das sind editierbare Produktvorgaben, keine Schätzung persönlicher Geh-/Fahrgeschwindigkeit. Pufferbereich 0–180 Minuten; Entfernungen positiv und endlich. Keine willkürliche harte Entfernungssperre für bewusst manuell gewählte Modi.

Entscheidung in einem Durchlauf:

1. Explizite Auswahl Auto/Fuß/Rad hat immer Vorrang.
2. Bei Automatisch zunächst Luftlinie als kostengünstigen Ausschlussfilter verwenden. Außerhalb der Grenze keine unnötige Routinganfrage für diesen Modus.
3. Kandidaten in konfigurierbarer Priorität prüfen, Vorgabe Fuß vor Rad. Nur eine echte Route des jeweiligen Modus ist zulässig.
4. Maßgeblich für die Grenze ist die tatsächliche Weglänge. Luftlinie allein darf keine automatische Zusage auslösen, etwa bei Flüssen, Bahnanlagen oder Umwegen.
5. Erster erfolgreich berechneter Kandidat innerhalb seiner Grenze gewinnt. Exakte Grenzwerte zählen als innerhalb. Bei gleichem Start/Ziel ist ein Weg mit Dauer null plus gewähltem Puffer zulässig.
6. Außerhalb beider Grenzen Auto verwenden. Falls ein aktivierter Modus nicht verfügbar ist oder fehlschlägt, bei interaktiver Planung Grund anzeigen und die angebotene Alternative bestätigen lassen. Für unbeaufsichtigte Kalenderverarbeitung eigener Schalter „Bei nicht verfügbaren Fuß-/Radwegen Auto verwenden“, standardmäßig aus. Ohne Freigabe Vorgang als nicht berechenbar melden, keinen falschen Autotermin schreiben.

Keine Endlosschleife zwischen Modi und Anbietern. Ein gemeinsames begrenztes Anfragebudget gilt für den gesamten Auswahlvorgang. Erfolgreiche Geokodierung wird wiederverwendet. Abgebrochene/überholte Planungen dürfen weder Auswahl noch Kalender verändern.

## Architektur und Migration

- `TravelMode`: CAR, WALK, BICYCLE. AUTO ist eine Auswahlstrategie, kein Anbieterprofil. `RouteRequest` trägt gewünschten Modus bzw. Strategie; `RouteEstimate` den tatsächlich berechneten Modus und Anbieter.
- Reine, testbare Auswahlfunktion `TravelModeSelector` kapselt Priorität, Grenzen und Fehler-/Fallback-Entscheidung. Netzwerkzugriffe bleiben in der bestehenden Coroutine- und Routingarchitektur.
- Pro Anbieter/Endpunkt explizite Fähigkeiten mit verfügbaren Modi und Profilzuordnung. Die heutigen fest codierten `car`/`DRIVE`-Anfragen in `UnifiedRoutingService` und weiteren aktiven Adaptern systematisch ersetzen. Vor Implementierung offizielle Providerdokumentation, regionale Verfügbarkeit, Tarif/API-Version und benutzerdefinierte Profile prüfen.
- Ein OSRM-Endpunkt wird nicht durch bloßes Ändern eines URL-Wortes automatisch zum Fuß-/Radrouter. Endpunkte müssen das tatsächlich passende Routingprofil anbieten. Anbieter ohne bestätigte Unterstützung aus dem jeweiligen Fallback-Pfad ausschließen.
- Connectivity-Tests, Quoten, Auth-Scopes und Fehlerdiagnosen müssen Profil/Modus berücksichtigen. Nie still eine Autostrecke als Fuß-/Radstrecke ausgeben. Der vorhandene geschätzte Restfußweg von einer Ladesäule ersetzt keinen vollständigen Fußrouter.
- Einstellungen: je Modus Puffer, Automatikfreigabe, Entfernungsgrenze, Priorität und erlaubter unbeaufsichtigter Fallback. Bestehende Einstellungen erhalten, Altprofile ohne neue Felder auf CAR/Automatik aus migrieren.
- Cache und In-flight-Deduplizierung um tatsächlichen Modus und Profil ergänzen. Fahrzeugrouten, Fußrouten und Radrouten dürfen keinen gemeinsamen falschen Cachetreffer erzeugen.
- Historie und verschlüsseltes Backup versionieren, ältere Daten weiterhin lesen. Alte Historieneinträge gelten als CAR. Modus/Profil und Auswahlgrund in neuen Einträgen speichern, ohne alte Historie nachträglich umzuschreiben.
- Kalenderidentität bleibt beim logischen Weg zu einem Termin stabil. Moduswechsel aktualisiert den zugehörigen Weg statt einen zweiten Termin zu erzeugen. Export und Navigation erhalten passende Bezeichnungen und Transportparameter.
- Authentifizierte Automation kann optional einen Modus angeben; fehlendes Feld erhält das bisherige Verhalten. Ohne aktivierte Automatik keine neuen unbeaufsichtigten Fuß-/Radplanungen.
- Keine zusätzlichen Standortberechtigungen, keine Tracker, keine Übertragung von Kalenderkonto/-name, Titel oder Beschreibung an Routinganbieter. Diagnose weiter ohne private Koordinaten, Schlüssel oder Headerwerte.

## Umsetzungsschritte

1. Datenmodell, Migration und reine Auswahl-/Pufferlogik mit Unit-Tests.
2. Verifizierte Providerfähigkeiten, echte Fuß-/Radprofile, modusgetrennte Fallbacks/Caches und begrenzte Budgets.
3. Manuelle Auswahl und Ergebnisanzeige, danach Historie, ICS und Kalenderintegration.
4. Opt-in-Entfernungsautomatik und bestehende Hintergrund-Kalenderverarbeitung einschließlich Konflikt-/Fehlerpfaden.
5. Backup-/Automation-Kompatibilität, deutsche/englische UI und Android-Gerätetests.
6. Separate Debug-Abnahme vor einer weiteren öffentlichen Freigabe. Keine automatische Aktivierung durch ein Update.

## Abnahmetests

- CAR verhält sich bei alten Einstellungen unverändert; Automatik bleibt aus.
- Manuelle Fuß-/Radwahl bleibt bei jeder Entfernung erhalten; unabhängige Puffer beeinflussen nur ihren Modus.
- Abfahrtszeit einschließlich Mitternacht, Zeitzonen-/Sommerzeitwechsel, Puffer null und negativer/ungültiger Eingaben korrekt.
- Fuß/Rad getrennt aktivierbar, benutzerdefinierte Priorität, unter/auf/über jeder Grenze, nur ein aktiver Modus, gleicher Start/Ziel.
- Luftlinie innerhalb, tatsächlicher Umweg außerhalb: keine falsche automatische Auswahl.
- Nicht unterstütztes Profil, ungültige Providerantwort, Zeitüberschreitung, Offlinezustand und Quotenlimit ergeben verständliche Fehler und halten die Fallback-Freigabe ein.
- Keine Umwandlung von Autodauer/-geometrie in vermeintliche Fuß-/Radwerte; kein falscher Cachetreffer zwischen Modi.
- Beendete und überholte Anfragen schreiben weder UI-Auswahl noch Kalender zurück. Gemeinsames Anfragebudget und keine doppelten parallelen Requests.
- Historie, alter/neuer Backup-Import, Kalender-Deduplizierung und Moduswechsel funktionieren ohne Datenverlust oder doppelte Wege.
- Parkplatz-/Ladefunktionen bleiben für Auto erhalten und bleiben für reine Fuß-/Radwege aus.
- Compose-Tests für kleine Breite, große Schrift, Eingabeentwürfe und explizite Auswahl; echte Android-Tests für Hintergrundverarbeitung und Kalenderausgabe.

## English summary

Planned for the next major feature release, not implemented in 1.4.0: optional walking and cycling with independent buffers, genuine mode-specific routing and opt-in distance-based selection. Explicit manual selection always wins. Actual route length determines eligibility; straight-line distance is only a prefilter. Automation defaults off, respects existing location/privacy rules and never silently substitutes driving when a walking/cycling request fails. Separate capability checks, cache keys, migrations and regression tests are required.
