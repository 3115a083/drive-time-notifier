# Gespeicherte Planungen an Tasker oder Home Assistant übergeben

## Einrichtung

**Einstellungen → Automatische Verarbeitung → Gespeicherte Planungen weitergeben** öffnet die Konfiguration.

- Broadcast-Intent und HTTPS-Webhook lassen sich unabhängig aktivieren. Beide sind zunächst ausgeschaltet.
- Eine gemeinsame Feldauswahl bestimmt die Daten beider Ausgaben. Die Vorschau zeigt ausschließlich Beispieldaten.
- Die Weitergabe folgt einer erfolgreichen Erstellung oder Aktualisierung einer Kalenderfahrt oder einem ICS-Export. Eine reine Routenberechnung sendet nichts.
- Kalenderfahrten zum selben Termin mit identischen normalisierten Start- und Zieladressen werden aktualisiert. Die Kalendertermin-ID und damit `plan_id` bleiben erhalten. Andere Termine und Startorte bleiben getrennt.
- Die Webhook-URL und ein optionales Bearer-Token werden mit dem Android Keystore verschlüsselt gespeichert. Die Schnittstellenkonfiguration wird nicht in den bisherigen Einstellungsbackup exportiert und muss auf einem anderen Gerät neu eingerichtet werden.

## Tasker

Vorgaben:

- Aktion: `de.drivetime.notifier.PLAN_SAVED`
- Empfängerpaket: `net.dinglisch.android.taskerm`

In Tasker ein Profil **Ereignis → System → Intent Received** mit dieser Aktion anlegen. Kategorien, URI-Schema und MIME-Typ leer lassen. Taskers Überwachung muss aktiv sein.

Die App sendet einen Broadcast, eingeschränkt auf das gewählte Paket, ohne feste Receiver-Komponente. Ausgewählte JSON-Felder liegen zugleich als Intent-Extras vor. Tasker stellt sie als lokale Variablen bereit, etwa `%start_address`, `%destination_address`, `%start_time_ms`, `%arrival_time_ms`, `%plan_id` und `%revision_ms`. `%payload` enthält das gesamte JSON. Ein Unix-Zeitwert in Sekunden ergibt sich aus `%start_time_ms / 1000`; beispielsweise lässt sich daraus eine Weckzeit 30 Minuten vor Abfahrt berechnen.

Das Paket und die Aktion sind für andere Broadcast-Empfänger änderbar. Ein gesendeter Broadcast ist keine Empfangsbestätigung und weckt keine absichtlich gestoppte App auf.

## Home Assistant / HTTPS-Webhook

Eine Webhook-Automation einrichten und deren vollständige HTTPS-URL in der App eintragen, etwa:

`https://ha.example.org/api/webhook/EINE_LANGE_ZUFAELLIGE_GEHEIME_ID`

Home-Assistant-Webhooks benötigen normalerweise kein zusätzliches Bearer-Token. Andere Dienste können das optionale Token im `Authorization: Bearer …`-Header verwenden. Nabu-Casa-Webhook-URLs sind ebenfalls möglich. Die App setzt keine TLS-Prüfung außer Kraft und folgt keinen Weiterleitungen. Lokale Klartext-HTTP-URLs werden nicht unterstützt.

Die App sendet `POST` mit `Content-Type: application/json; charset=utf-8`. Home Assistant liest die Werte über `trigger.json`. Für direkte Zugriffe aus dem Internet muss die Webhook-Konfiguration dies erlauben; Home Assistants lokale Beschränkung und die gewählte URL müssen zur gewünschten Nutzung passen.

Beispiel, das einen zuvor angelegten Datums-/Zeit-Helfer für die Weckzeit aktualisiert:

```yaml
alias: Weckzeit aus Fahrtplanung
triggers:
  - trigger: webhook
    webhook_id: EINE_LANGE_ZUFAELLIGE_GEHEIME_ID
    allowed_methods:
      - POST
    local_only: true
conditions:
  - condition: template
    value_template: >-
      {{ trigger.json.start_time_ms is defined and
         (trigger.json.start_time_ms | float(0)) / 1000 > as_timestamp(now()) }}
actions:
  - action: input_datetime.set_datetime
    target:
      entity_id: input_datetime.fahrt_weckzeit
    data:
      timestamp: "{{ (trigger.json.start_time_ms | float(0)) / 1000 - 30 * 60 }}"
mode: queued
```

Der Helfer speichert die Zeit; eine separate Automation kann zu dieser Zeit die gewünschte Weckaktion ausführen. Das einfache Beispiel arbeitet mit dem zuletzt angenommenen Plan. Für mehrere Fahrten sollte die Zielautomation die passende nächste Fahrt auswählen und Zustellungen nach `plan_id` und `revision_ms` verwalten.

## Datenformat

Immer enthalten: `schema_version` (derzeit 1) und `delivery_id` (Zustellungs-ID). Alle folgenden Daten sind einzeln abwählbar.

| Auswahl | JSON-/Intent-Feld | Typ / Bedeutung |
| --- | --- | --- |
| Startadresse | `start_address` | Text |
| Zieladresse | `destination_address` | Text |
| Abfahrt | `start_time`, `start_time_ms` | UTC ISO 8601, Unix-Millisekunden |
| Ankunft | `arrival_time`, `arrival_time_ms` | Geplante Ankunft einschließlich Fußweg, vor Ankunftspuffer |
| Ursprünglicher Termin | `appointment_time`, `appointment_time_ms` | Beginn des Zieltermins |
| Stabile ID | `plan_id` | Unverändert bei Aktualisierung desselben Kalendertermins |
| Planungsrevision | `revision_ms` | Zeit-/Versionswert der erfolgreichen Speicherung |
| Vorgang | `operation` | `created`, `updated` oder `exported` |
| Dauer | `planned_duration_seconds` | Dauer des Fahrt-Kalendereintrags einschließlich möglichem Fußweg |
| Puffer | `buffer_minutes` | Tatsächlich angewendeter Ankunftspuffer |
| Termin-ID | `calendar_event_id` | Numerisch, bei Kalenderausgabe |
| Kalender-ID | `calendar_id` | Numerisch, bei Kalenderausgabe |
| Titel | `event_title` | Titel des gespeicherten Fahrttermins |
| Zeitzone | `timezone` | IANA-Zeitzone, etwa `Europe/Berlin` |

Kalender-IDs fehlen bei ICS-Exporten. Ursprüngliche Terminzeit und Puffer fehlen, wenn kein Ausgangstermin bekannt ist. Ohne ausgewählte Adressen/Titel werden diese weder im JSON noch als Intent-Extra übertragen.

```json
{
  "schema_version": 1,
  "delivery_id": "0d4da405-0000-4000-8000-000000000001",
  "start_address": "Beispielstraße 1",
  "destination_address": "Zielstraße 2",
  "start_time": "2026-10-15T06:45:00Z",
  "start_time_ms": 1792046700000,
  "arrival_time": "2026-10-15T07:30:00Z",
  "arrival_time_ms": 1792049400000,
  "plan_id": "calendar:7:42",
  "revision_ms": 1791972000000
}
```

## Zustellung und Aktualisierung

Der Kalender wird zuerst gespeichert. Danach verarbeitet ein separater Hintergrundauftrag die Weitergabe. Ein Webhook-Fehler macht den Kalendertermin nicht rückgängig.

- Erfolgreiche Webhooks liefern HTTP 2xx. Netzwerkfehler sowie HTTP 408, 429 und 5xx werden insgesamt höchstens dreimal versucht, mit wachsendem Abstand.
- Andere HTTP-Fehler einschließlich Weiterleitungen werden nicht wiederholt. Zustellungen nach mehr als zwei Stunden werden verworfen.
- Eine neue Speicherung ersetzt noch offene Zustellungen desselben Plans. Nach Deaktivierung oder Änderung der Konfiguration senden alte Aufträge nichts mehr.
- Alle Wiederholungsversuche derselben Zustellung verwenden dieselbe `delivery_id` und denselben `Idempotency-Key`-Header. Trotzdem kann ein Empfänger eine Anfrage bereits verarbeitet haben, bevor die Antwort verloren geht. Zielautomationen sollten Wiederholungen anhand der Zustellungs-ID erkennen und alte Revisionen desselben Plans ignorieren.
- `plan_id` empfiehlt sich als Schlüssel zum Aktualisieren eines bestehenden Alarms. Bei mehreren Terminen müssen mehrere IDs getrennt verwaltet werden.
- ICS verwendet eine stabile UID pro Termin und Route. Ob erneute Imports vorhandene Einträge aktualisieren, hängt vom Kalenderprogramm ab.

Der letzte Zustellungsstatus steht im Einstellungsdialog; technische Ergebnisse stehen zusätzlich in der Diagnose. URLs, Tokens und Nutzdaten erscheinen nicht im Zustellungsprotokoll.

## English quick setup

Open **Settings → Automatic processing → Forward saved plans**. Enable a package-scoped broadcast for Tasker and/or an HTTPS JSON webhook. Select which fields may leave the app. Tasker uses **Event → System → Intent Received**, action `de.drivetime.notifier.PLAN_SAVED`, with variables such as `%start_time_ms` and `%payload`. Home Assistant consumes the POST body through `trigger.json`.

Use the stable `plan_id` to update alarms, and `revision_ms`/`delivery_id` to reject stale or duplicate deliveries. Times include UTC ISO 8601 and Unix milliseconds. Forwarding follows successful calendar storage/update or ICS export and runs independently of route calculation. Both outputs default to off. HTTPS validation remains enabled; no redirects are followed. Webhook URLs/tokens are encrypted locally and must be configured again after moving to another device.

## Referenzen

- https://tasker.joaoapps.com/userguide/en/intents.html
- https://www.home-assistant.io/docs/automation/trigger/#webhook-trigger
- https://www.home-assistant.io/docs/automation/templating/
