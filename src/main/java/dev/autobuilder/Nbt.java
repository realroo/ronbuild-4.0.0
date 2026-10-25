package dev.autobuilder;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Minimal NBT reader. Compound -> Map, List -> List, arrays -> primitive arrays. */
final class Nbt {
    private Nbt() {}

    static Map<String, Object> read(InputStream raw) throws IOException {
        BufferedInputStream buf = new BufferedInputStream(raw);
        buf.mark(2);
        int b1 = buf.read();
        int b2 = buf.read();
        buf.reset();
        InputStream in = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(buf) : buf;
        DataInputStream data = new DataInputStream(new BufferedInputStream(in));
        if (data.readUnsignedByte() != 10) throw new IOException("Root tag is not a compound");
        data.readUTF(); // root name
        return compound(data);
    }

    static int intOf(Object o) {
        return ((Number) o).intValue();
    }

    private static Map<String, Object> compound(DataInputStream in) throws IOException {
        Map<String, Object> map = new HashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return map;
            String name = in.readUTF();
            map.put(name, payload(in, type));
        }
    }

    private static Object payload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] a = new byte[in.readInt()];
                in.readFully(a);
                return a;
            }
            case 8: return in.readUTF();
            case 9: {
                int t = in.readUnsignedByte();
                int n = in.readInt();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < n; i++) list.add(payload(in, t));
                return list;
            }
            case 10: return compound(in);
            case 11: {
                int[] a = new int[in.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[in.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("Unknown NBT tag type " + type);
        }
    }
}
