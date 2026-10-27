# Input editing contract and field audit

## Mandatory rule

The editor owns its raw draft throughout the editing session. Never set a field from a
stored value merely because typing paused, parsing failed, or a save completed. Empty text,
leading zeroes, pasted invalid text, selection, and cursor movement are valid intermediate
editor states. Validate separately; show errors without replacing input or writing invalid
values. Translated labels are presentation, not state keys. Persisted or asynchronously
loaded defaults may initialize only untouched drafts. Explicit import, reset, saved-place,
calendar selection, and history-template selection are deliberate replacement actions.

Settings controls must not mount with placeholder defaults before settings have loaded.
Debounced saves run only after an actual user edit. Do not add polling or timer-based
"editing finished" detection. A delayed location or calendar response must be rejected if
any newer edit occurred, including when the text later returns to its earlier value.

## Audit, 2026-10-27

| Fields | Ownership and correction |
|---|---|
| Arrival buffer and reminder lead | Shared NumberDraftField, raw saveable draft, 0..180 validation, no timer reset |
| Charging/parking result counts | Same component, 1..50 validation |
| Charging/parking custom distances | Same component, 100..10000 validation |
| Provider request caps and timeouts | Same component, 1..1000000 and 1..300 validation |
| Home name/address, calendar title, OSRM/Valhalla/Photon URLs | User-owned drafts; initial values cannot save themselves; explicit import resets deliberately |
| Provider API keys | Provider identity keys the edit session; save only edited drafts; never copy keys into diagnostics |
| Overpass endpoint list and split setting | Dialog drafts no longer reinitialize on persisted settings emissions |
| History entry limit | Shared draft ownership, 1..500 validation and explicit Apply; storage emissions cannot replace input |
| Planner start/destination | Direct user state; delayed location/calendar updates reject newer edits |
| System-overlay start/destination | Editing or selecting a place invalidates the pending location request |
| Overlay buffer | Existing local text state, validation on explicit planner action; no persistence loop |
| Backup password, saved-place name/address, exclusion pattern | Existing dialog-owned text, no persistence-to-field loop; retained |
| Address suggestions | Suggestions change the field only when selected explicitly; no automatic selection |

## Required regression cases

- Clear a stored number, pause longer than every debounce, then enter another number.
- Preserve invalid/overflowing values and leading zeroes without silently clamping them.
- Deliver old save echoes and unrelated settings changes during editing.
- Switch a translated label without resetting the draft.
- Insert text at a nonterminal cursor position across recomposition.
- Restore the editing session and preserve the raw text.
- Validate every supported numeric range before persistence.
- Ignore a delayed location result after edits, including edit-and-undo.

DraftValue and rememberTextDraft implement the common ownership rule. NumberDraftField is
the single numeric settings editor. FieldDraftTest and FieldDraftUiTest enforce the contract.

Concurrent settings saves apply only changed preference keys inside the DataStore transaction. A delayed edit of one field cannot undo an unrelated field. Explicit radius preset selection starts a new numeric edit session; persistence echoes never do. PreferencePatchTest covers overlapping saves.
