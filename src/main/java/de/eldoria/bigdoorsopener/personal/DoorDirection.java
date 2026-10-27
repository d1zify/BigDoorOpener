/*
 *     SPDX-License-Identifier: AGPL-3.0-only
 *
 *     Copyright (C) 2021 EldoriaRPG Team and Contributor
 */

package de.eldoria.bigdoorsopener.personal;

import java.util.Locale;

public enum DoorDirection {
    UP(1), DOWN(-1);

    private final int sign;
    DoorDirection(int sign) { this.sign = sign; }

    public float offset(int height, int step) {
        return (float) (sign * (height + 1) * Math.max(0, Math.min(20, step)) / 20.0);
    }

    public static DoorDirection parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "up", "cima" -> UP;
            case "down", "baixo" -> DOWN;
            default -> throw new IllegalArgumentException("Use cima ou baixo (up/down).");
        };
    }
}
