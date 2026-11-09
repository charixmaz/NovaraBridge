# NovaraBridge quest reward API (Paper 26.1.2)

NovaraJobs stores a **snapshot at acceptance** of each quest's optional `bonus-rewards` rule.
When that quest is claimed, Jobs atomically records a bridge outbox row in its
SQLite transaction. It calls:

`NovaraBridge.claimQuestReward(UUID player, String questId, String period, String rewardYaml)`

with the original YAML snapshot (root key `bonus`), never a client-authored
action string. Operation identity is `quest:<uuid>:<quest-id>:<utc-period>`.
The same NovaraBridge receipt journal used by job milestones and ranks stores
each external action and freezes the original plan. Repeated calls are idempotent.
Missing integrations or a full inventory defer delivery, while uncertain external
effects require owner reconciliation via `/nb rewards pending` and `resolve`.

Supported bonus keys: `tags`, `pouches`, `items`, `permissions`,
and `commands`. Do not duplicate a quest's standard job XP or Cash:
NovaraJobs already commits those in the existing quest claim transaction.
Only the owner is allowed to edit bonus rewards via NovaraJobs quest commands.

Deploy matching versions of NovaraJobs and NovaraBridge. Existing jobs and
rank milestone reward configuration remains unchanged.
