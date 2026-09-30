# Smart Router — Design

## What it is

Per Scott's 2026-09-29 directive: **N labeled keys per AI, round-robin +
429 failover, per-key spend/token budgets, offline models one-at-a-time by RAM.**

## Built (2026-09-30, tested)

**`omni/gateway/KeyRouter.kt`** — key-level routing within a provider.
- `KeyInstance`: one labeled key (`id`, `providerId`, `label`, `vaultRef`,
  optional `dailyTokenBudget`, optional `monthlySpendBudgetUsd`, `enabled`).
  Key material stays in the vault; this is only metadata + budgets.
- Round-robin across eligible keys, per-provider cursor.
- `record429()`: exponential backoff (30s base, doubling, 10min cap).
  Caller tries the next key IMMEDIATELY — no waiting.
- `recordSuccess()`: clears 429 cooldown, accumulates token/spend usage.
- `isEligible()`: skips disabled, cooling-down, over-budget keys.
- Daily/monthly counters reset on calendar rollover.
- Thread-safe. **10/10 unit tests pass** (`KeyRouterTest.kt`).

## Integration (to do)

### AiGateway key-level failover
Current flow: `Router.pickRoute` → provider → `credentials.apiKey(provider.apiKeyRef)`.
New flow:
1. `Router.pickRoute` → provider (unchanged).
2. `KeyRouter.pickKey(provider.id, keys)` → key instance.
3. `credentials.apiKey(key.vaultRef)` → key material.
4. On HTTP 429: `keyRouter.record429(key.id)` → `pickKey` again (same provider)
   → try next key. Only after ALL keys exhausted → next provider.
5. On success: `keyRouter.recordSuccess(key.id, promptTokens, completionTokens, spendUsd)`.

`ProviderConfig` needs `keys: List<KeyInstance>` (replacing single `apiKeyRef`,
or in addition for migration).

### Persistence (SocketStore)
- Key instances persist in SharedPreferences: `key_instance:<providerId>:<keyId>`
  → JSON `{label, vaultRef, dailyTokenBudget, monthlySpendBudgetUsd, enabled}`.
- Usage counters (tokens today, spend this month) persist too, so budgets
  survive restarts. Or: keep in-memory only, accept reset on restart.
  Recommendation: persist — budgets are meaningless if a restart resets them.
- UI: SocketBoardScreen gets "＋ ADD KEY" per provider → label + key (vault)
  + optional budgets. Key list shows status (ready / rate-limited Xs /
  budget exhausted) via `KeyRouter.status()`.

### Offline model manager (one-at-a-time by RAM)
- `OfflineModelManager`: `load(modelId)` checks `ActivityManager.memoryInfo`
  before loading; if `availMem < model.requiredRam + SAFETY_MARGIN`, refuse
  with a clear message ("Need 2GB free, have 800MB").
- `load()` unloads the current model first — only one resident at a time.
- Model registry carries `requiredRamMb` per model.

## Honest boundaries
- 429 detection requires the HTTP client to surface 429 distinctly from
  other errors (`GatewayException.retryable` exists; needs a `rateLimited`
  flag).
- Spend tracking needs the price table; unknown models record nothing
  (existing `SpendTracker` behavior — never invent prices).
- "Predictive" 429 avoidance: the exponential backoff IS the prediction
  (a key that 429'd recently is likely to 429 again). No magic.
