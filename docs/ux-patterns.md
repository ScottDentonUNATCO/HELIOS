# UX Patterns: What Makes an AI App Feel Effortless

Research distilled 2026-10-01 for the Helios overhaul. Read the winners, learn the
failures, apply as concrete changes — never as decoration.

---

## The 9 patterns that win

### 1. One box, zero ceremony (ChatGPT)
The product opens on the single thing it does. No config walls, no mode pickers
before first value. ChatGPT's deliberately minimalist single-input interface is
the reason hundreds of millions of people could start in seconds — like Google,
it prioritizes simplicity over exposing model controls.
*Source: Medium PM case study on ChatGPT trust/UX — https://medium.com/@perespanda/designing-for-trust-in-ai-turning-hallucinations-into-a-ux-advantage-f95a1efc3827*

**Helios application:** Chat tab is the default tab and must never be gated behind
setup again (SKIP FOR NOW was the right call — keep it). Every other tab is a
second tap away.

### 2. First value inside 60 seconds (onboarding research)
Elite products deliver the aha moment in under 5 minutes; on mobile, aim for
meaningful first value inside 60 seconds of first open. The rules: defer account
creation / key entry, "do-it, don't tell-it" (an interactive first task beats a
coach-mark tour), kill setup friction, allow "Skip for now."
*Source: onboarding/permission-priming research — https://github.com/halidsaglam/saglitzdesign-mcp/blob/HEAD/knowledge/ux/onboarding-permission-priming.md*

**Helios application:** the "60-SECOND KEY DROP" card on the Sockets tab
(pick → paste → auto-test → GO CHAT) and tappable starter prompts on the empty
Chat tab. A user should go from install to a working AI reply in about a minute.

### 3. Empty states are onboarding surfaces, not dead ends
A blank first screen is a dead end. Every empty state = one-line explanation +
one obvious primary action. The dominant AI-chat pattern is 3–6 starter prompt
chips on the welcome state, capability-spanning (OpenAI ChatKit start-screen
prompts, assistant-ui, Copilot Studio all converge here).
*Sources: https://github.com/runtypelabs/persona/blob/HEAD/docs/suggestions-ux-research.md and https://github.com/k-arthur/opencode-harness/blob/HEAD/docs/research/ai-coding-ux-patterns-report.md*

**Helios application:** the empty Chat tab now shows tappable starter prompts.
When no key is saved, the same surface becomes a one-tap funnel into socket
setup instead of an error later.

### 4. Time-to-first-token is the number users feel (ChatGPT streaming)
Nielsen's thresholds still govern: ~100ms feels instant, ~1s keeps flow,
disengagement climbs past ~10s. Token streaming collapses perceived wait because
the user starts reading immediately. Show a distinct pre-first-token state
(animated cursor / "Thinking…") so the gap never reads as a hang. Show the
stage, not a spinner ("Searching your documents…", "Reading 4 sources…").
*Sources: https://github.com/halidsaglam/saglitzdesign-mcp/blob/HEAD/knowledge/ux/ai-product-ux.md and https://github.com/alexvervloet/ai-engineering-deep-dive/blob/HEAD/docs/AI-UX.md*

**Helios application:** `RINGING THE PROVIDER…` status line while waiting for the
first token, then a served-receipt on every reply (`↑ N / ↓ M TOKENS · FIRST WORD
IN 0.8s`).

### 5. Interruptibility is mandatory, not optional (AI chat UX consensus)
The send button becomes a **stop-generation** button while the model streams.
Users must be able to cut off a wrong or overlong answer instantly and keep the
partial text. Keep input focus, scroll, and stop responsive during generation.
*Source: https://github.com/halidsaglam/saglitzdesign-mcp/blob/HEAD/knowledge/ux/ai-product-ux.md*

**Helios application:** SEND becomes a red-ink STOP while streaming; partial
answer is kept.

### 6. Show your work (Perplexity)
Perplexity's defining habit is showing its work — every claim carries a
citation, and that single habit is what separates it from chatbots that answer
from memory. Design principle: *the answer IS the interface*; every decision
reduces the gap between question and verified answer. Copy rules: precise, no
throat-clearing, dense, direct errors ("Something went wrong. Try again." — no
apology theater).
*Sources: https://dupple.com/reviews/perplexity and https://github.com/004mayank/product-teardowns/blob/HEAD/perplexity-teardown.md and https://github.com/h0neyp0t-466/design-systems/blob/HEAD/perplexity/DESIGN.md*

**Helios application:** the per-reply token/TTFT receipt; error copy that says
what broke and what to do next (already in `actionableGatewayError` — keep
extending it).

### 7. Make output touchable (Claude artifacts)
Artifacts turned chat from a transcript into a workshop: output you can see,
touch, and iterate on. The "make it real" moment is what converts a demo into a
tool.
*Source: Claude artifacts UX analyses — https://assets.nextleap.app/submissions/UXAnalysisofClaudesdocumentQAExperience-8284e771-c6b1-46a4-98f6-c2e532cc79b2.pdf*

**Helios application:** applied so far as receipts and stamps rather than a full
artifact pane; the Game Maker hub is the long-term home of this pattern. Not
forced into chat prematurely.

### 8. Progressive disclosure + consistency (Raycast)
Raycast's principles: fast, simple, delightful. Keyboard-first, opinionated
design system so every command "looks and feels the same," advanced features
revealed contextually, minimal chrome / maximum content. "The best interface is
no interface — you think of a task and it is done before you reach for the
mouse" (CEO Thomas Paul Mann).
*Sources: https://news.ycombinator.com/item?id=22466994 and https://dev.to/kafrontdev/raycast-the-mac-productivity-tool-you-didnt-know-you-needed-pni*

**Helios application:** the Zine component kit (one button, one card, one field
style everywhere) is the consistency layer — deepen it, never dilute it.
New surfaces reuse it instead of inventing chrome.

### 9. Speed as a feature; onboarding as product (Superhuman / Arc)
Superhuman: sub-50ms feedback, offline-capable local caching, a mandatory
concierge onboarding call users *praise* in reviews, the 40% "very disappointed"
PMF bar. Arc: playfulness with intent — scrappy Thursday release notes users
looked forward to as content, 1:1 onboarding during the beta, adaptive UI that
feels alive. What retained Arc users was personality + craft + community, not
novelty alone.
*Sources: https://github.com/dbmcco/pmf-panel/blob/HEAD/prompts/specialists/superhuman_method.md, https://ventureburn.com/superhuman-email-review/, https://github.com/dork-labs/dorkos/blob/HEAD/research/20260324_feature_discovery_ux_patterns.md*

**Helios application:** punk-rock zine IS the Arc-style personality — the
differentiator, not a coat of paint. Treat it as the retention mechanic: every
new surface must feel hand-made, never default-Material.

---

## The 5 anti-patterns (from the failures)

### A1. Ship a demo, charge full price (Rabbit R1)
MKBHD's verdict: "delivering such unfinished products that it actually makes
them nearly impossible to review… the thing you get at the beginning is like
borderline non-functional compared to all the promises." Rabbit's LAM was a
cool idea with no training data and no integrations; users paid full price for
"maybe someday."
*Source: https://www.Dexerto.com/tech/marques-brownlee-slams-another-ai-product-as-barely-reviewable-after-humane-ai-pin-controversy-2669843/*

**Helios rule:** never present a non-working flow as working. OAuth sockets say
"lands in a later build." On-device runtimes are labeled NOT YET BUILT. A socket
with no passing TEST is not "connected" — the UI must not imply it is.

### A2. Fight an ingrained behavior with no bridge (Humane AI Pin)
Humane's key error: trying to change the deeply ingrained phone-screen habit.
$699 + $24/month, slow, overheating — and the phone in your pocket already ran
the same model better. 10,000 units shipped against a 100,000 forecast; assets
sold to HP; units remote-bricked. Lesson: "AI hardware = a better AI interface
than your phone, otherwise it doesn't exist."
*Sources: https://bestintechnology.de/the-humane-ai-pin-was-always-destined-to-fail-heres-why/ and https://github.com/30xcompany/30x-ai-native-gtm-starter/blob/HEAD/examples/our-flavor/research/01-capability-obsolescence-and-icp.md*

**Helios rule:** Helios IS a phone app — the right side of this line. Don't
build flows that assume users abandon their existing tools; sockets *attach* to
them.

### A3. The walled garden with no integrations (Humane / Rabbit)
Humane: ecosystem "none, standalone." Rabbit: "limited integration… minor
partnerships." The products that got traction did one integrated job well
(Limitless: transcription straight into Zoom/Meet/Slack/email; $99; 100-hour
battery; generous free tier).
*Source: https://medium.com/@carsten.krause/lessons-every-tech-executive-must-learn-from-humanes-rapid-rise-and-even-faster-fall-by-carsten-409de5ec2331 and http://www.blessthisstuff.com/stuff/technology/misc-gadgets/limitless-ai-pendant/*

**Helios rule:** sockets are the anti-walled-garden. Socket setup friction is
therefore the #1 business risk in the app — hence the 60-second key drop. Every
new capability should arrive as a socket, not a hardcoded feature.

### A4. Silent degradation
Answering worse because a provider is down — and never mentioning it — "is the
one that costs you trust for good. Say it." Error, refusal, empty, and degraded
are four different states with four different messages; a refusal that reads
like an error makes users retry the same thing forever.
*Source: https://github.com/alexvervloet/ai-engineering-deep-dive/blob/HEAD/docs/AI-UX.md*

**Helios rule:** 429 failover, budget sit-outs, and fallback routing must be
visible in the UI (status stamps on key instances already do this — extend, don't
hide).

### A5. Latency is the product (Humane's laggy commands)
Every review of the Pin and the R1 led with slowness. On-device and cached
paths aren't optimizations — they're the product for anything interactive.
*Source: https://www.techradar.com/computing/artificial-intelligence/with-the-humane-ai-pin-now-dead-what-does-the-rabbit-r1-need-to-do-survive*

**Helios rule:** local-first where possible; never block the UI thread on the
network; every wait gets a status line, every reply gets a timing receipt.

---

## Applied to Helios (this workstream, 2026-10-01)

| # | Pattern | Change |
|---|---------|--------|
| 1 | First value < 60s | "60-SECOND KEY DROP" card pinned atop the Sockets tab: pick socket → paste key → auto-test → GO CHAT |
| 2 | Empty states as onboarding | Chat empty state: tappable starter prompts; keyless variant funnels to Sockets instead of erroring later |
| 3 | TTFT perception | `RINGING THE PROVIDER…` pre-token status line; `↑ N / ↓ M TOKENS · FIRST WORD IN 0.8s` receipt on every reply |
| 4 | Interruptibility | SEND becomes red-ink STOP while streaming; partial answer kept |
| 5 | Show your work | Receipts + exact test failures (already), extended to chat replies |
| 6 | Consistency | New surfaces reuse the Zine kit only — no new chrome |

Deliberately NOT changed: the WAKE UP key gate (SKIP FOR NOW already satisfies
"defer setup"); Hub/Memory/Eyes/Offline tabs (out of this workstream's scope);
OAuth login flows (need real platform work, still honestly labeled); a full
artifacts pane (Game Maker hub is its future home — not forced into chat).
