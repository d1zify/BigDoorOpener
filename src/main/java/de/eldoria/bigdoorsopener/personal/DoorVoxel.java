/*
 *     SPDX-License-Identifier: AGPL-3.0-only
 *
 *     Copyright (C) 2021 EldoriaRPG Team and Contributor
 */

package de.eldoria.bigdoorsopener.personal;

public record DoorVoxel(int x, int y, int z, boolean solid, String data) {
    private static final double EPS = 0.0001;

    public String encode() { return x + ";" + y + ";" + z + ";" + solid + ";" + data; }
    public static DoorVoxel decode(String encoded) {
        String[] parts = encoded.split(";", 5);
        if (parts.length != 5 || (!parts[3].equals("true") && !parts[3].equals("false")))
            throw new IllegalArgumentException("Snapshot de bloco inválido");
        return new DoorVoxel(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), Boolean.parseBoolean(parts[3]), parts[4]);
    }

    /** Segmento dos pés contra o bloco expandido pelo corpo. Evita atravessar em um tick. */
    public boolean crosses(double fx, double fy, double fz, double tx, double ty, double tz,
                           double halfWidth, double height) {
        if (!solid) return false;
        double[] from = {fx, fy, fz}, delta = {tx - fx, ty - fy, tz - fz};
        double[] min = {x - halfWidth + EPS, y - height + EPS, z - halfWidth + EPS};
        double[] max = {x + 1 + halfWidth - EPS, y + 1 - EPS, z + 1 + halfWidth - EPS};
        double enter = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(delta[axis]) < EPS) {
                if (from[axis] < min[axis] || from[axis] > max[axis]) return false;
            } else {
                double a = (min[axis] - from[axis]) / delta[axis];
                double b = (max[axis] - from[axis]) / delta[axis];
                enter = Math.max(enter, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
                if (enter > exit) return false;
            }
        }
        return true;
    }
}
