package dev.autobuilder;

import java.util.Arrays;
import java.util.Random;

/**
 * Produces the list of cells (dense indices) in the order they should be built,
 * without creating an object per block. With smart ordering every layer is built in
 * two passes: solid blocks first, fragile blocks (torches, doors, beds, gravity blocks...) after.
 */
final class BuildOrder {
    private BuildOrder() {}

    private static final class Sink {
        final Blueprint b;
        final int[] arr;
        final boolean skipAir, smart;
        int n;

        Sink(Blueprint b, boolean skipAir, boolean smart) {
            this.b = b;
            this.arr = new int[b.volume()];
            this.skipAir = skipAir;
            this.smart = smart;
        }

        int passes() {
            return smart ? 2 : 1;
        }

        void offer(int idx, int pass) {
            int v = b.cell(idx);
            if (v == Blueprint.NONE) return;
            if (skipAir && b.isAir(v)) return;
            if (smart && b.isFragile(v) != (pass == 1)) return;
            arr[n++] = idx;
        }
    }

    static int[] create(Blueprint b, BuildJob.Pattern pattern, int sweepX, int sweepZ,
                        boolean skipAir, boolean smart) {
        int w = b.width(), h = b.height(), l = b.length();
        boolean useSmart = smart && pattern != BuildJob.Pattern.RANDOM;
        Sink s = new Sink(b, skipAir, useSmart);
        int passes = s.passes();

        switch (pattern) {
            case DOWN -> {
                for (int y = h - 1; y >= 0; y--) layer(s, w, l, y, passes);
            }
            case RANDOM -> {
                for (int idx = 0; idx < b.volume(); idx++) s.offer(idx, 0);
                Random r = new Random();
                for (int i = s.n - 1; i > 0; i--) {
                    int j = r.nextInt(i + 1);
                    int t = s.arr[i];
                    s.arr[i] = s.arr[j];
                    s.arr[j] = t;
                }
            }
            case OUTWARD -> {
                if ((long) w * l >= (1 << 24)) {
                    for (int y = 0; y < h; y++) layer(s, w, l, y, passes);
                } else {
                    long[] keys = new long[w * l];
                    double cx = (w - 1) / 2.0, cz = (l - 1) / 2.0;
                    for (int z = 0; z < l; z++) {
                        for (int x = 0; x < w; x++) {
                            long d = Math.round(Math.hypot(x - cx, z - cz) * 100);
                            keys[z * w + x] = (d << 24) | (long) (z * w + x);
                        }
                    }
                    Arrays.sort(keys);
                    for (int y = 0; y < h; y++) {
                        for (int pass = 0; pass < passes; pass++) {
                            for (long k : keys) {
                                int col = (int) (k & 0xFFFFFF);
                                s.offer((y * l + col / w) * w + col % w, pass);
                            }
                        }
                    }
                }
            }
            case LEFT, RIGHT, NORTH, SOUTH, EAST, WEST -> {
                if (sweepX != 0) {
                    for (int i = 0; i < w; i++) {
                        int x = sweepX > 0 ? i : w - 1 - i;
                        for (int y = 0; y < h; y++) {
                            for (int pass = 0; pass < passes; pass++) {
                                for (int z = 0; z < l; z++) s.offer((y * l + z) * w + x, pass);
                            }
                        }
                    }
                } else if (sweepZ != 0) {
                    for (int i = 0; i < l; i++) {
                        int z = sweepZ > 0 ? i : l - 1 - i;
                        for (int y = 0; y < h; y++) {
                            for (int pass = 0; pass < passes; pass++) {
                                for (int x = 0; x < w; x++) s.offer((y * l + z) * w + x, pass);
                            }
                        }
                    }
                } else {
                    for (int y = 0; y < h; y++) layer(s, w, l, y, passes);
                }
            }
            default -> {
                for (int y = 0; y < h; y++) layer(s, w, l, y, passes);
            }
        }
        return Arrays.copyOf(s.arr, s.n);
    }

    private static void layer(Sink s, int w, int l, int y, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < l; z++) s.offer((y * l + z) * w + x, pass);
            }
        }
    }
}
