package dev.autobuilder;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.structure.StructureRotation;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A loaded structure (.schem, .litematic or vanilla .nbt) stored compactly:
 * one 16-bit palette index per cell instead of one object per block.
 */
public final class Blueprint {

    /** Cell value meaning "no block here" (nothing is placed and nothing is cleared). */
    public static final char NONE = 0xFFFF;
    private static final int MAX_PALETTE = 0xFFFE;

    private final String name, fileName;
    private final int width, height, length;
    private final char[] cells;
    private final BlockData[] palette;
    private final boolean[] air, fragile;
    private final int unknown;
    private final int blockCount;

    private Blueprint(String name, String fileName, int width, int height, int length,
                      char[] cells, BlockData[] palette, int unknown) {
        this.name = name;
        this.fileName = fileName;
        this.width = width;
        this.height = height;
        this.length = length;
        this.cells = cells;
        this.palette = palette;
        this.unknown = unknown;
        this.air = new boolean[palette.length];
        this.fragile = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) {
            air[i] = palette[i].getMaterial().isAir();
            fragile[i] = isFragile(palette[i]);
        }
        int count = 0;
        for (char c : cells) {
            if (c != NONE && !air[c]) count++;
        }
        this.blockCount = count;
    }

    public String name() { return name; }
    public String fileName() { return fileName; }
    public int width() { return width; }
    public int height() { return height; }
    public int length() { return length; }
    public int volume() { return cells.length; }
    public int blockCount() { return blockCount; }
    public int unknown() { return unknown; }
    public int sizeX(int rotation) { return rotation % 180 == 0 ? width : length; }
    public int sizeZ(int rotation) { return rotation % 180 == 0 ? length : width; }

    public char cell(int idx) { return cells[idx]; }
    public BlockData data(int idx) { return palette[cells[idx]]; }
    public BlockData state(int paletteIndex) { return palette[paletteIndex]; }
    public int paletteSize() { return palette.length; }
    public boolean isAir(int paletteIndex) { return air[paletteIndex]; }
    public boolean isFragile(int paletteIndex) { return fragile[paletteIndex]; }

    /** How many cells use each palette entry (NONE cells are not counted). */
    public long[] paletteCounts() {
        long[] counts = new long[palette.length];
        for (char c : cells) {
            if (c != NONE) counts[c]++;
        }
        return counts;
    }

    private static boolean isFragile(BlockData d) {
        Material m = d.getMaterial();
        if (m.isAir() || m == Material.WATER || m == Material.LAVA) return false;
        if (d instanceof Door || d instanceof Bed) return true;
        return !m.isSolid() || m.hasGravity();
    }

    /** A copy rotated clockwise by 0/90/180/270 degrees (positions and block states). */
    public Blueprint rotated(int degrees) {
        if (degrees == 0) return this;
        int nw = degrees == 180 ? width : length;
        int nl = degrees == 180 ? length : width;
        char[] out = new char[cells.length];
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int nx, nz;
                    switch (degrees) {
                        case 90 -> { nx = length - 1 - z; nz = x; }
                        case 180 -> { nx = width - 1 - x; nz = length - 1 - z; }
                        default -> { nx = z; nz = width - 1 - x; }
                    }
                    out[(y * nl + nz) * nw + nx] = cells[(y * length + z) * width + x];
                }
            }
        }
        StructureRotation rot = switch (degrees) {
            case 90 -> StructureRotation.CLOCKWISE_90;
            case 180 -> StructureRotation.CLOCKWISE_180;
            default -> StructureRotation.COUNTERCLOCKWISE_90;
        };
        BlockData[] np = new BlockData[palette.length];
        for (int i = 0; i < palette.length; i++) {
            BlockData c = palette[i].clone();
            try {
                c.rotate(rot);
            } catch (Throwable ignored) {
                // very old server: keep the block state unrotated
            }
            np[i] = c;
        }
        return new Blueprint(name, fileName, nw, height, nl, out, np, unknown);
    }

    public static Blueprint load(File file, int maxVolume) throws IOException {
        Map<String, Object> root;
        try (InputStream in = new FileInputStream(file)) {
            root = Nbt.read(in);
        }
        String fn = file.getName();
        String name = fn.replaceFirst("\\.[^.]+$", "");
        if (root.get("Schematic") instanceof Map<?, ?> inner) root = map(inner); // Sponge v3
        if (root.containsKey("Regions")) {
            try {
                return fromLitematic(name, fn, root, maxVolume);
            } catch (RuntimeException ex) {
                throw new IOException("This .litematic file could not be read (damaged or unsupported version). "
                        + "Try exporting it as .schem.", ex);
            }
        }
        if (root.containsKey("Width")) return fromSponge(name, fn, root, maxVolume);
        if (root.containsKey("size")) return fromStructure(name, fn, root, maxVolume);
        throw new IOException("Unrecognised format (expected .schem, .litematic or vanilla .nbt)");
    }

    private static void checkVolume(long vol, int max) throws IOException {
        if (vol <= 0 || vol > max) {
            throw new IOException("Schematic is too large (" + vol + " cells, limit " + max + ")");
        }
    }

    /** Palette that turns block state strings into compact indices. */
    private static final class Pal {
        final Map<String, Character> index = new HashMap<>();
        final List<BlockData> list = new ArrayList<>();
        int unknown = 0;

        char of(String state) {
            Character known = index.get(state);
            if (known != null) return known;
            char result;
            try {
                BlockData d = Bukkit.createBlockData(state);
                if (list.size() >= MAX_PALETTE) throw new IllegalArgumentException("palette full");
                result = (char) list.size();
                list.add(d);
            } catch (IllegalArgumentException ex) {
                unknown++;
                result = NONE;
            }
            index.put(state, result);
            return result;
        }

        BlockData[] toArray() {
            return list.toArray(new BlockData[0]);
        }
    }

    private static Blueprint fromSponge(String name, String fn, Map<String, Object> root, int maxVolume)
            throws IOException {
        int w = Nbt.intOf(root.get("Width"));
        int h = Nbt.intOf(root.get("Height"));
        int l = Nbt.intOf(root.get("Length"));
        long vol = (long) w * h * l;
        checkVolume(vol, maxVolume);

        Map<String, Object> paletteMap;
        byte[] data;
        if (root.containsKey("Blocks")) {
            Map<String, Object> blocks = map(root.get("Blocks"));
            paletteMap = map(blocks.get("Palette"));
            data = (byte[]) blocks.get("Data");
        } else {
            paletteMap = map(root.get("Palette"));
            data = (byte[]) root.get("BlockData");
        }
        if (paletteMap == null || data == null) throw new IOException("Schematic has no block data");

        Pal pal = new Pal();
        int max = 0;
        for (Object v : paletteMap.values()) max = Math.max(max, Nbt.intOf(v));
        char[] pm = new char[max + 1];
        Arrays.fill(pm, NONE);
        for (Map.Entry<String, Object> e : paletteMap.entrySet()) {
            pm[Nbt.intOf(e.getValue())] = pal.of(e.getKey());
        }

        char[] cells = new char[(int) vol];
        Arrays.fill(cells, NONE);
        int pos = 0, idx = 0;
        while (pos < data.length && idx < cells.length) {
            int value = 0, shift = 0;
            byte b;
            do {
                b = data[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && pos < data.length);
            cells[idx++] = value < pm.length ? pm[value] : NONE;
        }
        return new Blueprint(name, fn, w, h, l, cells, pal.toArray(), pal.unknown);
    }

    @SuppressWarnings("unchecked")
    private static Blueprint fromStructure(String name, String fn, Map<String, Object> root, int maxVolume)
            throws IOException {
        List<Object> size = (List<Object>) root.get("size");
        int w = Nbt.intOf(size.get(0));
        int h = Nbt.intOf(size.get(1));
        int l = Nbt.intOf(size.get(2));
        long vol = (long) w * h * l;
        checkVolume(vol, maxVolume);

        List<Object> pal = root.containsKey("palette")
                ? (List<Object>) root.get("palette")
                : (List<Object>) ((List<Object>) root.get("palettes")).get(0);
        Pal p = new Pal();
        char[] pm = new char[pal.size()];
        for (int i = 0; i < pm.length; i++) pm[i] = p.of(stateString(map(pal.get(i))));

        char[] cells = new char[(int) vol];
        Arrays.fill(cells, NONE);
        for (Object o : (List<Object>) root.get("blocks")) {
            Map<String, Object> b = map(o);
            List<Object> pos = (List<Object>) b.get("pos");
            int x = Nbt.intOf(pos.get(0)), y = Nbt.intOf(pos.get(1)), z = Nbt.intOf(pos.get(2));
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l) continue;
            int st = Nbt.intOf(b.get("state"));
            cells[(y * l + z) * w + x] = st < pm.length ? pm[st] : NONE;
        }
        return new Blueprint(name, fn, w, h, l, cells, p.toArray(), p.unknown);
    }

    @SuppressWarnings("unchecked")
    private static Blueprint fromLitematic(String name, String fn, Map<String, Object> root, int maxVolume)
            throws IOException {
        Map<String, Object> regions = map(root.get("Regions"));
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Object o : regions.values()) {
            Map<String, Object> r = map(o);
            Map<String, Object> pos = map(r.get("Position")), size = map(r.get("Size"));
            int sx = Nbt.intOf(size.get("x")), sy = Nbt.intOf(size.get("y")), sz = Nbt.intOf(size.get("z"));
            int px = Nbt.intOf(pos.get("x")) + (sx < 0 ? sx + 1 : 0);
            int py = Nbt.intOf(pos.get("y")) + (sy < 0 ? sy + 1 : 0);
            int pz = Nbt.intOf(pos.get("z")) + (sz < 0 ? sz + 1 : 0);
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            minZ = Math.min(minZ, pz);
            maxX = Math.max(maxX, px + Math.abs(sx) - 1);
            maxY = Math.max(maxY, py + Math.abs(sy) - 1);
            maxZ = Math.max(maxZ, pz + Math.abs(sz) - 1);
        }
        int w = maxX - minX + 1, h = maxY - minY + 1, l = maxZ - minZ + 1;
        long vol = (long) w * h * l;
        checkVolume(vol, maxVolume);

        char[] cells = new char[(int) vol];
        Arrays.fill(cells, NONE);
        Pal pal = new Pal();

        for (Object o : regions.values()) {
            Map<String, Object> r = map(o);
            Map<String, Object> pos = map(r.get("Position")), size = map(r.get("Size"));
            int sx = Nbt.intOf(size.get("x")), sy = Nbt.intOf(size.get("y")), sz = Nbt.intOf(size.get("z"));
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            int px = Nbt.intOf(pos.get("x")) + (sx < 0 ? sx + 1 : 0);
            int py = Nbt.intOf(pos.get("y")) + (sy < 0 ? sy + 1 : 0);
            int pz = Nbt.intOf(pos.get("z")) + (sz < 0 ? sz + 1 : 0);

            List<Object> lp = (List<Object>) r.get("BlockStatePalette");
            char[] local = new char[lp.size()];
            for (int i = 0; i < local.length; i++) local[i] = pal.of(stateString(map(lp.get(i))));

            long[] states = (long[]) r.get("BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(lp.size() - 1));
            long mask = (1L << bits) - 1;
            int total = ax * ay * az;
            for (int i = 0; i < total; i++) {
                long startBit = (long) i * bits;
                int startLong = (int) (startBit >>> 6);
                int offset = (int) (startBit & 63);
                if (startLong >= states.length) break;
                long value;
                if (offset + bits <= 64) {
                    value = (states[startLong] >>> offset) & mask;
                } else {
                    if (startLong + 1 >= states.length) break;
                    value = ((states[startLong] >>> offset) | (states[startLong + 1] << (64 - offset))) & mask;
                }
                int vi = (int) value;
                char g = vi < local.length ? local[vi] : NONE;
                if (g == NONE) continue;
                int x = i % ax, z = (i / ax) % az, y = i / (ax * az);
                int idx = ((y + py - minY) * l + (z + pz - minZ)) * w + (x + px - minX);
                boolean isAir = pal.list.get(g).getMaterial().isAir();
                if (isAir && cells[idx] != NONE) continue; // do not let an empty region erase another
                cells[idx] = g;
            }
        }
        return new Blueprint(name, fn, w, h, l, cells, pal.toArray(), pal.unknown);
    }

    private static String stateString(Map<String, Object> c) {
        StringBuilder sb = new StringBuilder((String) c.get("Name"));
        if (c.get("Properties") instanceof Map<?, ?> props && !props.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Map.Entry<?, ?> pe : props.entrySet()) {
                if (!first) sb.append(',');
                sb.append(pe.getKey()).append('=').append(pe.getValue());
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }
}
