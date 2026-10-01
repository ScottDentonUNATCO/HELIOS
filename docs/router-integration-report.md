# Smart Router — Integration Report

**Date:** 2026-10-01
**Scope:** Wire the `KeyRouter` (multi-instance keys, round-robin + 429 failover, per-key budgets) into the Helios app. Gateway module was already integrated in a prior pass; this report covers the app wiring + tests.

## What was wired

**Gateway (prior pass, verified this pass):**
- `AiGateway.generate` routes at key level: null/blank model = auto per-provider default (`ProviderConfig.models.firstOrNull()`); per provider it tries each key instance in turn; `rateLimited` (HTTP 429, now surfaced distinctly via `GatewayException.rateLimited`) records 429 backoff and retries the next key of the SAME provider immediately; pure key exhaustion does not mark the provider sick at Router level.
- `KeyRouter`: round-robin cursor per provider, exponential 429 backoff (30s base, 10min cap), per-key daily-token / monthly-spend budgets, `restoreUsage`/`snapshotAll` for restart persistence (429 cooldowns deliberately NOT persisted — restart gives keys a fresh chance).
- `SpendTracker`: **per-1K-token USD pricing** (deliberate unit change from per-1M), plus `unknownPricedCalls`/`hasUnknownPricedUsage()` so unknown models read "spend unknown", never $0-forever.
- `ModelCatalog`: per-socket default models + curated per-1K price estimates (labeled ESTIMATES).
- `AnthropicClient`: real native Messages API adapter (system→top-level param, role merging, SSE `text_delta`→tokens, usage from `message_start`/`message_delta`, real `GET /v1/models`); `clientForProvider` routes `anthropic` to it, everything else to OpenAI-compat.
- Dead config removed: `KeyRouterConfig.allowed429s` was never read (`record429` always backs off immediately — the failover design needs that). Removed rather than wired: tolerating N 429s before cooling would retry a just-throttled key on the next call.

**App wiring (this pass):**
- `AndroidVault`: `instanceRef(providerId, keyId) = "vault:<providerId>:key:<keyId>"`, per-instance save/read, `deleteApiKey`.
- `SocketStore`: key-instance CRUD in prefs (`socket_key_instances:<providerId>`), usage snapshots (`key_usage:<keyId>`), `hydrateKeyRouter`, one-time legacy migration (old single key → "Default" instance pointing at the same vault ref; key material never moves).
- `SocketBoardViewModel`: owns the shared `KeyRouter` (hoisted by MainActivity, hydrated once); instance add/remove/enable; `runTest` routes through the router with the price table + Anthropic `clientFor` + usageListener; `defaultTestModel` kept as a forwarder to `ModelCatalog` (gamemaker's `LlmDraft` still compiles).
- `SocketBoardScreen`: `KeyRow` replaced by `KeyInstancesSection` — labeled list with live status (1s ticker: ready / rate-limited Ns / budget exhausted), per-key usage, enable/disable/remove, add form (label + key + optional budgets).
- `MainActivity.buildGateway`: per-socket `ProviderConfig` carries `keys`; shared hoisted `KeyRouter`; `ModelCatalog.PRICE_TABLE_USD_PER_1K`; `clientForProvider`; `usageListener` persists snapshots. `ChatScreen` → `vm.send()`.
- `ChatViewModel.send(model: String? = null)`: null = auto per-provider default model.
- `HubViewModel`: `def.model ?: ModelCatalog.defaultModelFor(def.id)`; deleted its `DEFAULT_MODEL` const.
- `SafetyScreen`: "Includes usage the price table can't estimate — actual spend is higher." when unknown-priced calls exist; "No spend recorded yet." when total is $0.

## Exact test results (run, not invented)

Gateway suite — **all green**:
| Class | Tests |
|---|---|
| AiGatewayKeyRoutingTest (new) | 9/9 |
| AnthropicClientTest (new) | 7/7 |
| KeyInstanceCodecTest (new) | 5/5 |
| KeyRouterPersistenceTest (new) | 3/3 |
| ModelCatalogTest (new) | 3/3 |
| RateLimitSignalTest (new) | 3/3 |
| SpendTrackerTest (updated per-1K + unknown tracking) | 5/5 |
| AiGatewayTest (price expectation 4.0 → 4000.0) | 8/8 |
| KeyRouterTest | 10/10 |
| KeyRouterIntegrationTest (2-line per-1K math reconciliation) | 9/9 |
| OpenAiCompatClientTest | 7/7 |
| RouterTest | 10/10 |

Full suite (`run-tests.sh` equivalent, all modules): **356 run, 7 failures — all 7 in files outside this integration's scope**, plus one sibling test file that currently blocks `run-tests.sh` compilation:
- `nes/test/.../ConsoleContractCheckTest.kt` — sibling's in-flight gamemaker refactor (`GoodPlugin` went final, `checkConsoleContract` renamed); does not compile, so `run-tests.sh` aborts at test-compile. Excluded it to run the rest.
- `FetchLinkTest` (4), `ComfyUiClientTest` (1), `VisionRequestTest` (1), `WellKnownSocketsTest` (1) — pre-existing failures in code paths this integration didn't touch (FetchLink inspector, ComfyUI client, OpenAI vision body shape, offline-tool catalog). Flagged for their owners; none are router regressions (verified: my OpenAiCompatClient change is limited to the `httpError` delegation — request-body building untouched).

Test-infra note: real socket servers can't bind in this sandbox (even loopback), so HTTP-level tests run through OkHttp-interceptor mocks (`MockOpenAiBackend`, new `MockAnthropicBackend` mirroring it). `compile-gateway.sh` and `compile-app.sh` both complete clean.

## Still stubbed / needs Scott
- **His real keys.** Everything above runs on dummy key material. The Test button on the Sockets tab is the dead-simple flow: add a key instance → TEST → exact provider error or "ok".
- **Price table is estimates** (`ModelCatalog` says so in code). Real spend = provider dashboards.
- **Anthropic adapter is untested against the real API** — wire shape verified against the mock only. First real Anthropic key test will confirm/deny.
- Per-key monthly spend budgets use estimated prices; a provider price change drifts the budget math. Honest direction, not a bug.

## Files changed (this pass)
Gateway main: `KeyRouter.kt` (dead config removed), `KeyInstanceCodec.kt` (`persistedKeyUsageNow`).
Gateway tests: `MockAnthropicBackend.kt` (new), `AnthropicClientTest.kt` (new), `AiGatewayKeyRoutingTest.kt` (new), `RateLimitSignalTest.kt` (new), `KeyInstanceCodecTest.kt` (new), `KeyRouterPersistenceTest.kt` (new), `ModelCatalogTest.kt` (new), `SpendTrackerTest.kt` (updated), `AiGatewayTest.kt` (updated), `KeyRouterIntegrationTest.kt` (2-line per-1K reconciliation).
App: `AndroidVault.kt`, `SocketStore.kt`, `SocketBoardViewModel.kt`, `SocketBoardScreen.kt`, `MainActivity.kt`, `ChatViewModel.kt`, `HubViewModel.kt`, `SafetyViewModel.kt`, `SafetyScreen.kt`.
