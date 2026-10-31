# Tile settings and portable profiles

The Android **설정** button opens the native tile screen. The same entry is used
by the device list's settings shortcut. It reads `/api/settings` from the selected
device, classifies its current catalog into seven functional groups, and keeps
unknown/older groups under **기타·구버전 설정**. Development options retain their
own first category. No firmware catalog, personal values, or default snapshot is
packaged into this screen.

The screen reads values once when opened or explicitly refreshed. Missing or
non-integer values are not replaced with defaults and cannot be edited here;
**기존 설정 화면** retains access to the original device web controls. Firmware
may return its own default for an unset key. Stored values and static dependency
notes do not prove that a driving feature is currently active.

Wide displays use up to four columns; parameter cards adapt to available width.
Advanced coefficients are collapsed but remain searchable. Each item preserves
the connected firmware's original description, name, range, and increment. Human
time/distance/acceleration labels are display conversions; edits use the original
integer units and reject out-of-range input without clamping.

An individual edit needs explicit confirmation. The original device identity and
active selection are checked, the value is re-read, and a single existing
`/api/param_set` request is followed by readback. Failed/uncertain requests are
never automatically retried. The firmware's existing parked/control checks remain
authoritative. This existing endpoint does not provide an atomic revision-based
compare-and-set against other clients; the new UI does not claim to add one.

**프로필5개** is directly visible in the toolbar and in a home tile. Each card
shows the combination's intent and selected values and opens the existing native
profile editor with that profile selected. It does not immediately apply it.
Existing preview, revision/serial binding, full27-key validation, pending evidence,
readback, recovery, import/export, and widget selection behavior are retained.
User-imported profiles and cached unverified/recovery warnings are preserved.

Opening, refreshing, rotating, or updating the app never applies a profile or
changes driving settings. The Android app update is independent of the OP
firmware update. No new firmware, Panda, CAN, monitoring, archive, or overlay
processing is introduced by this screen.
