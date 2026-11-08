package dev.novara.unified;

import java.util.*;

/** Only dedicated bridge groups may be changed. Source groups would create feedback. */
public final class RankGroups {
    private RankGroups() {}
    public static Map<Integer,String> validate(Map<Integer,String> groups, Set<String> sourceAndPaid) {
        Set<String> used = new HashSet<>();
        for (var entry : groups.entrySet()) {
            String group = entry.getValue();
            if (entry.getKey() < 1 || entry.getKey() > 10 || group == null
                    || !group.matches("[a-z0-9_-]+") || !used.add(group)
                    || sourceAndPaid.contains(group) || group.equals("default"))
                throw new IllegalArgumentException("Invalid, duplicate, source or paid rank group: " + group);
        }
        if (groups.isEmpty()) throw new IllegalArgumentException("rank-groups must not be empty");
        return Map.copyOf(groups);
    }
}

