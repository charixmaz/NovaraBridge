# NovaraBridge 0.1.1

Unified optional integrations for Paper 26.1.2 / Java 25:

- NovaraRanksTags progression rank -> NovaraJobs job slots, banking and loan rank policies.
- NovaraRanksTags -> dedicated LuckPerms benefit groups.
- NovaraJobs payouts, debits and balance reads -> NovaraBridge -> Vault -> EssentialsX.
- AstralLeaderboard job snapshots and balance reads -> NovaraBridge.

ArcaneAlchemy is outside this release. No balances, rank ownership or job progress are copied into the bridge.

## Installation

1. Stop the server and back up plugin data.
2. Remove the old `NovaraRanksBridge` JAR. Do not run both bridges.
3. Install NovaraBridge and the companion Jobs, Ranks & Tags 0.7.0, and AstralLeaderboard builds from this release. Replace existing JARs; do not leave duplicate versions.
4. Keep LuckPerms, EssentialsX and Vault (or a compatible VaultUnlocked build) installed.
5. In `plugins/NovaraRanksTags/config.yml`, set `ranks-test-mode: false`. Existing configs are preserved, so this must be changed explicitly. The bridge refuses test-mode rank benefits.
6. Start the server. Run `/novarabridge status` as an admin.

Jobs automatically uses the unified rank provider when this connection is ready; no edit to Jobs `ranks.yml bridge.enabled` is required. Existing rank benefit amounts and job-slot limits remain configured in NovaraJobs.

## Ranks and groups

The source of progression rank remains NovaraRanksTags. Its `role-ranks` mapping of inherited LuckPerms groups takes precedence. Otherwise it uses the selected rank only when the player has `novara.ranks.rank.N`; unverified selections previously made in test mode fall back to rank 1. Paid rank groups never determine progression rank unless an admin explicitly puts them in the source plugin's role-ranks mapping.

The bridge creates dedicated `novarabridge_rank_1` through `novarabridge_rank_7` groups. Add gameplay benefit permissions to those groups using LuckPerms. It never changes primary group, prefixes, tags or paid groups. It manages only direct membership nodes in the configured dedicated group names; reserve those names for this purpose.

Do not put the dedicated groups in `role-ranks` or `paid-ranks`, or make them inherit source progression groups. This would create rank feedback. Direct name collisions are rejected. Do not grant `novara.ranks.rank.N` or legacy `novarajobs.rank.N` permissions through dedicated groups; grant unlock permission through your existing progression rules instead.

Example: `/lp user PLAYER parent add rank2` with `role-ranks.rank2: 2` makes the source plugin show Rank 2. Within five seconds, the bridge assigns `novarabridge_rank_2`, and Jobs uses the rank-2 limits. The same flow handles downgrades; Jobs suspends excess job slots according to its existing policy.

Synchronization checks online users every five seconds and saves only when managed memberships differ. Saves are asynchronous. Offline users are reconciled when they next join. Disabling synchronization stops future changes; it does not remove already granted groups.

## Commands

- `/novarabridge status` or `/nb status`: connection diagnostics and your progression rank.
- `/novarabridge reload`: reload connection toggles and group mapping; restart to change sync interval.
- Permission: `novarabridge.admin`, default operators only.

Each connection can be disabled separately in bridge `config.yml`. If the bridge is present but economy is disabled/unavailable, updated consumers do not silently bypass it. Without the bridge, companion plugins keep their existing standalone integrations.

## Economy and failure behavior

Only an enabled Vault Economy service registered by Essentials/EssentialsX is accepted. Transactions use integer cents and one Vault call per attempt, with no automatic replay. The bridge does not schedule additional payments or replace Jobs' existing payout journal. It cannot make Vault transfers crash-atomic or guarantee deduplication for arbitrary callers; callers must keep their existing operation IDs/journals. No economy mutation command is exposed.

## Validation

Run `mvn clean verify` in this repository with Java 25 and Maven. The bridge discovers the Jobs rank interface at runtime; no private NovaraJobs source or locally installed Jobs Maven artifact is required. Compile companion projects separately. Automated tests cover group protection and economy validation/provider failure behavior. Before production, test on a copy of the server:

1. `/nb status` reports all installed connections ready.
2. A regular non-OP Rank 1 player has one job slot; assign source Rank 2 and verify the configured rank-2 job and bank limits.
3. Downgrade the source rank and verify old managed membership is removed while paid groups remain.
4. Earn a job payout: EssentialsX balance increases once, and the Jobs journal records the payout.
5. Refresh Astral with its existing refresh command; verify highest job level and balance rows.
6. Restart and confirm ranks, tags, money and job progress persist.
7. On the test server, remove an optional dependency and restart: `/nb status` must explain the missing connection, with no invented balances or repeated payments.

This release does not implement rank purchasing/unlock requirements or add new rewards.

## Companion integration pull requests

- [NovaraJobs](https://github.com/charixmaz/NovaraJobs/pull/30)
- [Novara Ranks & Tags](https://github.com/charixmaz/Novara-Ranks-Tags/pull/2)
- [AstralLeaderboard](https://github.com/charixmaz/AstralLeaderboard-/pull/3)

The bridge source, tests and build workflow are maintained in this repository. Companion changes stay in their respective repositories.


## 0.1.1 seven-rank defaults
Adds novarabridge_rank_6 and novarabridge_rank_7 to default group mappings.
Startup and reload add missing defaults while preserving owner overrides and disabled integrations.
Use with Novara Ranks & Tags 0.7.0 for seven-rank progression.
No economy or rank benefit amounts changed; existing Jobs/bank policies still apply.


## Astral reward delivery

Configure `rewards` in AstralLeaderboard's config, then set `rewards.enabled: true`. Cash values use dollars (5000 means $5,000); commands run as console without a leading slash and accept `{player}` / `{uuid}`. Add the actual commands for your paid-rank group, tag system and plushie/item plugin. Empty command lists grant no rank, tag or item. The top-three Cash defaults are 0, 5000, 2000. Configure each category separately under `rewards.categories.<board>.<place>` when different boards need different prizes.

After archiving a season, use `/astral reward <season> <blocks|balance|mcmmo|jobs> <place> deliver`. Requires `astral.admin`. The bridge must also have `integrations.astral-rewards: true`. `paid` and `unpaid` retain their manual bookkeeping behavior; they do not grant prizes. This change adds manual delivery, not an automatic season scheduler or rewards display.

Bridge receipts in `reward-journal.properties` bind each season/category/place to a recipient and exact action plan. Completed steps are skipped on repeat calls; steps left PENDING after a crash, command failure or ambiguous response require staff reconciliation against the actual economy/permission/item state. Do not erase receipts to retry. Already executed console commands cannot be rolled back. A command returning success only confirms dispatch; use the installed plugin's documented grant commands and verify them on a test server. Item commands may require an online player with inventory space; plan their use accordingly.

Paid ranks and tags should use their own grant commands. Example for a defined tag permission: `lp user {uuid} permission set novara.tags.tag.unforgiven true` (the corresponding tag must exist in Ranks & Tags config). Configure the real paid group and duration explicitly; the bridge does not guess them. Plushies require your actual item plugin command.
