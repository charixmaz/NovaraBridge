# Novara configurable rewards — Paper 26.1.2 / Java 25

## Install all four JARs

Install **NovaraJobs**, **Novara Ranks & Tags** (Bukkit plugin `NovaraRanksTags`), **NovaraPouches**, and **NovaraBridge**; also the pre-existing **LuckPerms**, **EssentialsX**, and **Vault/VaultUnlocked** providers. Use the builds from the same feature release. **Remove the old `NovaraRanksBridge.jar`** — it conflicts with the unified NovaraBridge integration. The bridge discovers Jobs and Ranks dynamically; do not add circular soft dependencies. Restart Paper after changing plugin jars.

**Source repositories:** charixmaz/NovaraJobs, charixmaz/Novara-Ranks-Tags, charixmaz/NovaraPouches, charixmaz/NovaraBridge. Maven runs on Java 25, Paper 26.1.2 API.

## Exact owner-editable YAML paths

- `plugins/NovaraBridge/rewards.yml` — **the single authoritative reward definition file** for job levels and rank upgrades. New milestones, money, tags, item counts, pouch rarities, permissions, and console commands require YAML edits only.
- `plugins/NovaraRanksTags/config.yml` — define the **tag IDs** referenced in rewards.yml, e.g. `tags.miner` and `tags.hunter`. This defines cosmetics and permissions, not reward grants.
- `plugins/NovaraPouches/config.yml` — defines pouch tier aesthetics and contents. The bridge validates against tier IDs.
- `plugins/NovaraJobs/milestones.yml` — existing **one-time job XP bonus rules**, separate from new external milestone gifts.
- `plugins/NovaraBridge/reward-journal.properties` — durable delivery receipts; NEVER edit or delete to retry a grant without reconciling it.
- `plugins/NovaraJobs/novarajobs.db` (or the configured SQLite filename): `nj_job_reward_claims` persists crossed milestones/bridge handoff.
- `plugins/NovaraRanksTags/players.db`: stored earned ranks, pending upgrade gifts, and tag ownership.

When updating existing JARs, pre-existing YAML files are **not overwritten**. Copy the new reward definitions into `rewards.yml` and add missing `miner` / `hunter` tags into Ranks & Tags `config.yml`. Always back up the old databases and reward journal before deployment.

## Example rewards.yml

```yaml
jobs:
  MINER:
    '30':
      tags: [miner]
      pouches:
        - rarity: uncommon
          quantity: 1
  HUNTER:
    '50':
      tags: [hunter]
      pouches:
        - rarity: legendary
          quantity: 1
ranks:
  '2':
    pouches:
      - rarity: common
        quantity: 1
```

Add/edit another milestone or rank gift:

```yaml
jobs:
  FARMER:
    '20':
      money: 250.00
      pouches:
        - rarity: epic
          quantity: 2
      items:
        - material: DIAMOND
          amount: 3
      permissions: [starfall.farmer.special]
      commands:
        - 'say {player} reached Farmer level 20'
```

The example above is a **fragment** to merge under the existing `jobs:` key; do not create a second top-level `jobs:` in YAML. Valid built-in pouch tier IDs: `common`, `uncommon`, `epic`, `legendary`, `apex`. Pouch quantity is 1–36 per reward entry (one pouch per inventory slot); item quantities are 1–64 and need sufficient space for their stack size. For more than 36 pouches, use multiple separate reward entries and allow the player to free slots between deliveries. Quantity and stack limits are validated. Console commands have **no leading /**. Placeholder variables: `{player}`, `{uuid}`. Only server owners should edit reward commands.

## How delivery works

1. NovaraJobs enqueues every crossed job level into its existing asynchronous SQLite worker. Rank upgrades enqueue their reward intent in the same durable transaction as the earned-rank change. **No external payout occurs before that source claim is saved.**
2. The source plugin calls `NovaraBridge.claimJobMilestone` / `claimRankUpgrade`. Bridge freezes the YAML reward plan in its existing reward journal, keying it by player and job/level or rank, *before executing an external action*.
3. Bridge grants each step. Tag ownership is saved by Novara Ranks & Tags and appears in the player's **/tags collection without auto-equipping**. Pouches are created through NovaraPouches with deterministic item tokens and normal pouch redemption behaviour; they are not opened automatically. Money goes through NovaraBridge's existing EssentialsX-owned Vault path. Permissions use LuckPerms. Items are ordinary inventories. Optional commands run once as console.
4. A successful step is marked `DONE`. A temporarily **missing plugin, unknown tag or tier, offline recipient or full inventory** leaves the step `READY` and retries later without dropping or repeating prior successful steps.
5. If the server stops or an external call becomes uncertain **after** recording `IN_FLIGHT`, it is never automatically retried (Vault, console commands and external side effects cannot provide universal exactly-once guarantees). Admins inspect and manually reconcile before resuming. This is deliberate protection against duplicate credits/rewards.

The immutable claim means changing rewards.yml affects only **future** milestone claims. Already queued or successfully delivered milestones keep their original contents. Editing a pouch tier's configured opening rewards is separate from handing out pouch items.

### Retroactive policy

**No automatic retroactive rewards.** Players already above a configured job level or already holding a rank when installing this release do not automatically receive those new prizes. Future level crossings/upgrades are rewarded. For an explicit backfill, while the player is online, use:

- `/nj rewards grant-missed <player> <job> <level>` — queue one specific old job milestone, even when the player switched jobs; e.g. `/nj rewards grant-missed Steve MINER 30`.
- `/nv rewards grant-missed <player> <rank>` — queue an already earned rank, e.g. `/nv rewards grant-missed Steve 2`. Rank must have been earned.

Claims remain keyed to the same identity even after leaving/rejoining a job, restarting, retrying, or issuing the same admin command. Old completed steps are **not replayed**. Admins may add a newly configured prize to a formerly empty milestone through backfill.

### Commands and diagnostics

- `/nb rewards reload` — validates and reloads `plugins/NovaraBridge/rewards.yml`, retaining last good settings if invalid. Same via `/nj rewards reload` or `/nv rewards reload`.
- `/nb rewards status` — report pending/review count and missing integrations, invalid tag IDs or absent tier IDs.
- `/nb rewards pending <online-player>` — list outstanding operation IDs and per-step statuses.
- `/nb rewards resolve <operation> <index> done|retry` — **admin-only** reconciliation. First inspect the other plugin's actual state. `done` records a confirmed external grant; `retry` should be used **only if** staff have verified that no reward was delivered. Unverified retry can duplicate Cash/items.
- `/nj validate`, `/nb status`, `/pouches status`, `/nv status` — inspect relevant plugin health.

Only the owner with `novarabridge.admin`, `novarajobs.admin` or `novara.tags.admin` should use these admin commands. The reward configuration is presented in Jobs and Ranks GUIs automatically; edit only YAML and reload.

## Verification checklist

1. Test on a backed-up staging server with all four updated plugin JARs; ensure `/nb status` reports the installed dependencies.
2. Start a new Miner, reach level 30 and confirm `miner` appears in `/tags`, **without changing the selected tag**; check exactly one uncommon pouch in inventory. Repeat for Hunter level 50 and Rank 2 common pouch.
3. Repeat the level after switching jobs (or run the same admin missed-grant command twice): no repeat tag, pouch or Cash.
4. Fill inventory before a new reward: no pouch/item should be dropped or lost; empty slots and confirm the exact remaining gift is later delivered.
5. Disable NovaraPouches before reaching a milestone and later enable it: the tag (if successful) is not regranted and the waiting pouch is delivered once.
6. If a money/command transfer is uncertain, inspect bridge journal and external plugin state before using `resolve`; never wipe the journal.
7. Edit `rewards.yml` amount/rarity and run `/nb rewards reload`; verify the updated reward on a **new** player claim and unchanged prior claims.
8. Try invalid rarity/tag/item or missing dependency, verify descriptive error/status, and ensure last valid config remains running.
9. Profile TPS with Spark during multiple level-ups; all Jobs and Ranks state I/O uses existing serial workers; no synchronous SQL/Vault per mining action.

**Validation:** Maven `clean verify` on Java 25. Runtime full-inventory, crash recovery, tag collection and economy transfers must also be verified in Paper — these are external side effects that unit tests alone cannot prove.
