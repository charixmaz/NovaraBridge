package dev.novara.unified;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class RankSync {
    private final NovaraBridgePlugin plugin;
    private final LuckPerms permissions;
    private final Map<Integer,String> groups;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;
    private volatile String state = "Preparing dedicated groups";
    RankSync(NovaraBridgePlugin plugin, Map<Integer,String> groups) {
        this.plugin = plugin; this.groups = groups;
        permissions = Objects.requireNonNull(Bukkit.getServicesManager().load(LuckPerms.class), "LuckPerms unavailable");
        java.util.concurrent.CompletableFuture.allOf(groups.values().stream()
                .map(permissions.getGroupManager()::createAndLoadGroup)
                .toArray(java.util.concurrent.CompletableFuture[]::new))
            .whenComplete((ignored,error) -> {
                ready = false;
                if (error != null) {
                    state = "Group creation failed: " + error.getClass().getSimpleName();
                    return;
                }
                // Configure incremental perks and chain groups, then allow user reconciliation.
                // No permissions or roles outside our dedicated groups are touched.
                var saves = new ArrayList<java.util.concurrent.CompletableFuture<?>>();
                try {
                    for (var entry : groups.entrySet()) {
                        var group = permissions.getGroupManager().getGroup(entry.getValue());
                        if (group == null) throw new IllegalStateException("Missing created group " + entry.getValue());
                        if (entry.getKey() > 1 && groups.containsKey(entry.getKey()-1))
                            group.data().add(InheritanceNode.builder(groups.get(entry.getKey()-1)).build());
                        var section = plugin.getConfig().getStringList("rank-permissions."+entry.getKey());
                        for (String node : section) {
                            if (!node.matches("[a-z0-9_.-]+"))
                                throw new IllegalArgumentException("Invalid rank permission for " + entry.getKey()+": "+node);
                            group.data().add(net.luckperms.api.node.types.PermissionNode.builder(node).build());
                        }
                        saves.add(permissions.getGroupManager().saveGroup(group));
                    }
                } catch (RuntimeException invalid) {
                    state = "Rank permission config invalid: " + invalid.getMessage();
                    return;
                }
                java.util.concurrent.CompletableFuture.allOf(saves.toArray(java.util.concurrent.CompletableFuture[]::new))
                    .whenComplete((saved,saveFailure)->{
                        ready = saveFailure == null;
                        state = ready ? "Ready" : "Rank permission save failed: " + saveFailure.getClass().getSimpleName();
                    });
            });
    }
    String status() { return state; }
    void tick() {
        if (!ready) return;
        for (var player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            int rank;
            try { rank = plugin.progressionRank(id); }
            catch (RuntimeException unavailable) { continue; }
            String selected = groups.get(rank);
            if (selected == null) continue;
            var cached = permissions.getUserManager().getUser(id);
            var wantedNode = InheritanceNode.builder(selected).build();
            if (cached != null) {
                var managed = cached.data().toCollection().stream().filter(n ->
                        n instanceof InheritanceNode inherited && groups.containsValue(inherited.getGroupName())).toList();
                if (managed.size() == 1 && managed.getFirst().equals(wantedNode)) continue;
            }
            if (!pending.add(id)) continue;
            // Reconcile edits as well as rank changes; modifyUser persists asynchronously.
            permissions.getUserManager().modifyUser(id, user -> {
                var wanted = InheritanceNode.builder(selected).build();
                for (var node : List.copyOf(user.data().toCollection())) {
                    if (node instanceof InheritanceNode inherited && groups.containsValue(inherited.getGroupName())
                            && !node.equals(wanted)) user.data().remove(node);
                }
                user.data().add(wanted);
            }).whenComplete((ignored,error) -> {
                pending.remove(id);
                if (error != null) {
                    state = "Last save failed: " + error.getClass().getSimpleName();
                    plugin.getLogger().warning("Rank group save failed for " + id + "; next reconciliation will retry");
                } else state = "Ready";
            });
        }
    }
}

