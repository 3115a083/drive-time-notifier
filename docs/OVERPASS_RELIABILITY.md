# Overpass reliability, 2026-11-02

Confirmed code defect: the old client call timeout was nine seconds (read timeout eight), but Overpass may queue a request for fifteen seconds before execution. The old query execution limit was seven seconds and server memory limit eight MiB. Endpoint checks used a different twelve-second client, forced HTTP/1.1 and did not reject JSON runtime errors returned with HTTP 200.

Fixed: shared GET/POST request builder and JSON validation for both connectivity tests and real queries. Small encoded queries use GET; larger queries use POST. No forced close/identity headers or HTTP/1.1. Call budget fifty seconds, read budget forty-five, connect budget eight, query execution twenty seconds and server memory thirty-two MiB. Transfer size remains bounded at four MB. Cancellation closes the call. HTTP 429/406 cool down at least sixty seconds, respecting numeric Retry-After up to fifteen minutes. Sequential endpoint fallbacks remain bounded. Successful exact queries are cached for five minutes in a maximum sixteen-entry memory cache.

Small fixed-node probes from this build environment: main endpoint POST HTTP 502; GET HTTP 200 after about thirty-eight seconds. Private.coffee timed out for POST and GET; VK Maps returned HTTP 504 for both. These are point-in-time observations from this network, not proof of the user's device failure or permanent server unavailability. No private calendar coordinates were probed.

Public community services can still be overloaded. Each visible endpoint card has its own test and diagnostic result. Custom endpoints are HTTPS-only. Do not bypass quotas, pin individual backend hosts to evade limits, or automatically retry throttled servers.

References:
- https://dev.overpass-api.de/overpass-doc/en/preface/commons.html
- https://dev.overpass-api.de/command_line.html
- https://wiki.openstreetmap.org/wiki/Overpass_API
