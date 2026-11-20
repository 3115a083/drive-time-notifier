# Input editing contract

Read [docs/INPUT_EDITING.md](docs/INPUT_EDITING.md) before changing forms or settings.

User-entered drafts must never be replaced by a timer, persistence echo, background result,
recomposition, translated label, or temporary default. Preserve empty and invalid intermediate
text and cursor/selection. Keep raw UI text separate from validated persisted values. Apply
external values only to untouched drafts, or through an explicit user reset/import/selection.
Delayed requests must reject results after any newer edit. Cover confirmed defects with
unit and real Compose/Android regression tests. Run the relevant existing checks.
