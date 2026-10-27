/*
 *     SPDX-License-Identifier: AGPL-3.0-only
 *
 *     Copyright (C) 2021 EldoriaRPG Team and Contributor
 */

package de.eldoria.bigdoorsopener.personal;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public record DoorAccessRule(String permission, Set<UUID> players) {
    public DoorAccessRule {
        permission = permission == null ? "" : permission.trim();
        players = Set.copyOf(players);
    }

    public boolean allows(UUID player, Predicate<String> hasPermission) {
        return players.contains(player) || (!permission.isBlank() && hasPermission.test(permission));
    }
}
