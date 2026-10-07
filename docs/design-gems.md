# Design Gems — canon applied to Helios

Study notes from the design canon, each landed as a concrete UX change. Every
gem below names the principle, the violation/finding in Helios, the change,
and the files touched. Punk zine stays the house style — these gems deepen
structure and interaction, never dilute the aesthetic (the art worker owns
visual treatment; nothing here fights the four skins).

**Canon sources** (own knowledge, grounded against the web where it matters):
- Dieter Rams, *10 principles of good design*: innovative, useful, aesthetic,
  understandable, unobtrusive, honest, long-lasting, thorough to the last
  detail, environmentally friendly, as little design as possible.
- Jony Ive / Apple HIG: deference (UI defers to content), clarity, depth;
  the "it just works" bar — sensible defaults over configuration.
- Don Norman, *The Design of Everyday Things*: affordances, signifiers,
  mapping, feedback; the gulfs of execution and evaluation; forcing
  functions; Norman doors (a control that doesn't signal how it works).
- Steve Krug, *Don't Make Me Think*: eliminate question marks — every tap
  self-evident; omit needless words; never ask the user a question the app
  can answer itself.
- Edward Tufte: maximize the data-ink ratio — above all else, show the data;
  small multiples; no chartjunk.
- Zen of Palm: radical mobile minimalism — do one thing well, fewer steps,
  the cluttered screen is the enemy.
- Teenage Engineering: playfulness as a feature — tactile joy, satisfying
  physical interaction (the OP-1's crank), industrial design that invites play.
- Punk zine (house style): urgency, authenticity, hand-made > polished.

---

## Gem 1 — Rams "understandable" + Norman signifier: Chat says what it needs

**Finding.** First-run has SKIP FOR NOW (Scott's call, 2026-09-30): you can
explore keyless. But the Chat tab then shows a "KEY" button that says nothing
about *why* chat would fail — a gulf of evaluation. The user taps SEND,
gets an error, and has to infer the cause.

**Change.** `ChatScreen` takes a live `hasKey` (re-read from the vault
whenever the key-gate state changes). No key → the button reads **ADD KEY**
and a stamped one-liner sits under the header: *"No key — chat needs one.
ADD KEY to wake it up — or keep exploring, no key needed."* The button
labels the situation; the stamp closes the gulf.

**Files.** `omni/app/src/main/java/com/omni/app/MainActivity.kt`
(`ChatScreen`, call site in tab 0).

---

## Gem 2 — Krug: the Hub answers its own socket question

**Finding.** The Hub's "New task" form forced a socket pick even when exactly
one enabled CHAT socket existed — a question mark Krug would kill. Worse, the
empty-state dropdown item ("No enabled CHAT sockets") *looked* tappable and
did nothing: a textbook **Norman door**.

**Change.** When `chatSockets.singleOrNull()` exists and the user hasn't
picked, it's pre-selected (an explicit pick is never overwritten). The empty
menu item is now genuinely `enabled = false`, the field reads "No chat
sockets enabled", and a hint line under the picker says where to go
("flip one ON in the Sockets tab"). The queue-task error for the empty case
names the fix instead of the generic "Pick a socket".

**Files.** `omni/app/src/main/java/com/omni/app/hub/HubScreen.kt`.

---

## Gem 3 — Rams "unobtrusive" + "environmentally friendly": the ticker diet

**Finding.** Every socket card ran an **infinite 1-second `while(true)`
coroutine** to render 429 backoff countdowns — 25+ coroutines waking the UI
thread every second, forever, even when nothing was rate-limited. Unobtrusive
it was not; on a phone it's battery and thermal waste.

**Change.** The tick is now 1s **only while a key is in a live backoff**
(`isRateLimitedStatus`), otherwise a 15s heartbeat just to notice new
backoffs (e.g. from background Hub tasks). The effect restarts on the
backoff flag, so countdowns still tick live when visible.

**Files.** `omni/app/src/main/java/com/omni/app/sockets/SocketBoardScreen.kt`
(`KeyInstancesSection`); `isRateLimitedStatus` in
`omni/app/src/main/java/com/omni/app/ux/DesignGems.kt`.

---

## Gem 4 — Tufte: spend and usage become data bars

**Finding.** Spend was a flat string — `"Spent $2.50 / $5.00 budget"` — and
per-key usage was a concatenated run
(`••••1234 · 1500 tokens today · $0.1234 this month`): numbers with no
proportion, the data-ink ratio of a receipt.

**Change.** New `ZineMeter` (single fraction) and `ZineSegmentMeter`
(stacked proportions) in the zine library. The Safety spend card is now a bar
against the cap (red when over budget) with the numbers as its caption.
Every key instance gets two bars: tokens-today vs daily budget, spend vs
monthly budget; no budget configured → honest copy instead of a bar, never
a fake 0%. Pure helpers (`budgetFraction`, `tokenBudgetFraction`,
`spendBudgetFraction`, `formatCompactUsd`, `formatCompactTokens`) are
division-by-zero-safe and JVM-tested.

**Files.** `omni/app/src/main/java/com/omni/app/gamemaker/core/zine/ZineComponents.kt`
(`ZineMeter`, `ZineSegmentMeter`);
`omni/app/src/main/java/com/omni/app/safety/SafetyScreen.kt`;
`omni/app/src/main/java/com/omni/app/sockets/SocketBoardScreen.kt`
(`KeyInstanceRow`); `omni/app/src/main/java/com/omni/app/ux/DesignGems.kt`.

---

## Gem 5 — Zen of Palm: the socket board declutters itself

**Finding.** The Socket board — the most cluttered screen in the app — showed
the full KEYS form + add-key form + TEST row on **every** card, all the time.
Twenty-five sockets × three sections = a wall of forms.

**Change.** New `ZineExpander`: a labeled, tappable, arrow-signified strip
(never mystery-meat). KEYS & TEST live behind one expander per card,
**open by default only when the socket has zero keys** (guided setup for the
new — `defaultKeysExpanded`), collapsed when configured. The header always
shows the count: "Keys & test (3)".

**Files.** `omni/app/src/main/java/com/omni/app/gamemaker/core/zine/ZineComponents.kt`
(`ZineExpander`);
`omni/app/src/main/java/com/omni/app/sockets/SocketBoardScreen.kt`
(`SocketCard`); `defaultKeysExpanded` in `ux/DesignGems.kt`.

---

## Gem 6 — Norman signifier: TEST can't be a dead tap

**Finding.** TEST was always enabled. With no key it always failed with "No
key saved for 'X'. Add one below first." — the app offered a tap it knew
would fail. A control that can't work must say so *before* the tap.

**Change.** New `SocketBoardViewModel.hasSavedKey()` (the same vault check
`runTest()` uses). TEST is disabled without a saved key, and the idle hint
reads "Add a key above — TEST has nothing to send without one."

**Files.** `omni/app/src/main/java/com/omni/app/sockets/SocketBoardViewModel.kt`;
`omni/app/src/main/java/com/omni/app/sockets/SocketBoardScreen.kt` (`TestRow`).

---

## Gem 7 — Krug: Safety answers its own refresh question

**Finding.** The Safety screen had a REFRESH button next to RESET SPEND
whose job was re-reading the socket list — a control the app can operate
itself. Every manual refresh button is a question mark ("why must *I* do
this?").

**Change.** REFRESH is deleted. `LaunchedEffect(Unit) { vm.refreshSockets() }`
re-reads the list every time the screen is entered (tab switches dispose and
recreate the branch, so the effect refires). RESET SPEND stays — it's a
deliberate user action, not housekeeping.

**Files.** `omni/app/src/main/java/com/omni/app/safety/SafetyScreen.kt`.

---

## Gem 8 — Teenage Engineering: buttons slam like rubber stamps

**Finding.** Zine buttons were visually loud but physically dead — no press
feedback at all. TE's lesson: joy lives in the *feel* of the control, and a
punk zine app's natural metaphor is the rubber stamp.

**Change.** `ZineButton` (non-ransom path; the ransom tape button is the art
worker's file and untouched) now squashes to 93% with +1° rotation on press
and springs back on release (`spring(StiffnessMediumLow)`), with the press
ripple removed — the squash *is* the feedback. Disabled buttons stay dead
still: a dead control must look dead (Norman).

**Files.** `omni/app/src/main/java/com/omni/app/gamemaker/core/zine/ZineComponents.kt`
(`ZineButton`).

---

## Gem 9 — Norman forcing function + Rams "thorough": destructive taps confirm

**Finding.** REMOVE CUSTOM SOCKET and per-key REMOVE were one-tap destructive:
keys, budgets, and usage history gone with no forcing function. Memory's
delete already had the N7 confirm dialog; the socket board didn't — Rams
would call that not thorough to the last detail.

**Change.** Both removes now open the same confirm pattern as Memory (N7):
"Remove key 'Personal'? Its usage history and budgets go with it." /
"Remove {name}? Its keys and budgets go with it." REMOVE / KEEP buttons.

**Files.** `omni/app/src/main/java/com/omni/app/sockets/SocketBoardScreen.kt`
(`SocketCard`, `KeyInstanceRow`).

---

## Gem 10 — Rams "as little design as possible": Eyes fine print folds away

**Finding.** The Eyes screen ended with three stacked limitation paragraphs
(black secure windows, re-consent after reboot, notification STOP) —
permanent visual noise for copy you read once. Krug: omit needless words;
Rams: as little design as possible.

**Change.** The honest copy is unchanged but now lives behind a
`ZineExpander("Fine print")`, collapsed by default. Nothing lost, noise gone.

**Files.** `omni/app/src/main/java/com/omni/app/eyes/EyesScreen.kt`.

---

## Gem 11 — Tufte: the Memory screen shows its data

**Finding.** The Memory header was a wall of text:
"N records · SCOPE: n · ... \nCompressor pass (2000-token budget): keeps X,
folds Y scope summaries, drops Z". Counts were detached from the chips they
described; the compressor preview had no proportion.

**Change.** Counts moved **onto** the scope chips — "All (12)", "CHAT (7)" —
one glance, no separate sentence. The compressor preview is now a
`ZineSegmentMeter`: kept / folded / dropped as proportional segments
(`compressorSegments`, NaN-safe, JVM-tested) with the full honest copy
("nothing is ever deleted") as its caption.

**Files.** `omni/app/src/main/java/com/omni/app/memoryui/MemoryScreen.kt`;
`compressorSegments` in `omni/app/src/main/java/com/omni/app/ux/DesignGems.kt`.

---

## Deliberately NOT changed

- **The 8-tab bottom nav.** Zen of Palm itches to cut it, but the tabs are
  Scott-set product surface (Chat, Sockets, Memory, Eyes, Offline, Hub,
  Safety, More). Structure changes there need his call, not a worker's.
- **The kill-switch two-tap arm.** Krug says remove confirmations; Norman
  says the big red button is exactly where a forcing function belongs. The
  forcing function wins.
- **Ransom-skin `RansomTapeButton`.** The art worker owns visual treatment;
  the stamp-press (Gem 8) skips that path rather than fighting their file.
- **First-run SKIP FOR NOW.** Scott's explicit directive; Gem 1 works *with*
  it instead of relitigating it.

---

## Verification (2026-10-01)

- `apk-build/compile-app.sh` — green (full app sources, incl. all gem
  changes, compose against classes-gateway).
- New JVM suite `nes/test/com/omni/app/ux/DesignGemsTest.kt`: **22/22 tests
  pass** (budget fractions incl. NaN/zero-budget guards, compact USD/token
  formatting, compressor segments incl. NaN safety, 429-backoff tick policy,
  key-expander default policy).
- Two build-break repairs made to shared files while verifying (not design
  changes): removed a duplicated `ZineThemeBeige` declaration in
  `gamemaker/core/zine/ZineTheme.kt` (stale paste-duplicate left by a
  concurrent edit — kept the newer block with the v9 notes), and added the
  missing `import com.omni.app.gamemaker.core.ZineStamp` in
  `sockets/SocketBoardScreen.kt` (used by the concurrent worker's
  QuickStartCard).
- UI-level verification is composition only (no Compose UI-test infra in the
  repo); on-device feel (stamp-press animation, meter readability, Moto
  thermals) is unverified in this workstream.
