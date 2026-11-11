package fr.coffres.project;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Minimal reader for Minecraft's NBT format, used by .litematic, .schem, .schematic and structure .nbt files.
 * No library needed. Tags become plain Java values:
 * byte → Byte, short → Short, int → Integer, long → Long, float → Float, double → Double, string → String,
 * byte/int/long arrays → byte[] / int[] / long[], list → List, compound → Map (keeps the file order).
 * <p>
 * Files sent by players are not trusted: the unpacked size is capped and array lengths are checked
 * before anything is allocated, so a small malicious file cannot fill the server's memory.
 */
public final class Nbt {

    /** A file that is not NBT, is damaged, or is too big once unpacked. The message is shown to players (French). */
    public static final class NbtException extends IOException {
        public NbtException(String message) {
            super(message);
        }
    }

    private static final int MAX_DEPTH = 64;

    private final DataInputStream in;
    private final Limited counter;

    private Nbt(InputStream raw, long maxBytes) {
        this.counter = new Limited(raw, maxBytes);
        this.in = new DataInputStream(new BufferedInputStream(counter, 64 * 1024));
    }

    /** Reads a whole NBT file (gzip, zlib or uncompressed). Returns the root compound. */
    public static Map<String, Object> read(byte[] file, long maxUnpackedBytes) throws IOException {
        if (file == null || file.length < 3) {
            throw new NbtException("Le fichier est vide.");
        }
        InputStream raw = new ByteArrayInputStream(file);
        int b0 = file[0] & 0xFF;
        int b1 = file[1] & 0xFF;
        try {
            if (b0 == 0x1F && b1 == 0x8B) {
                raw = new GZIPInputStream(raw, 64 * 1024);
            } else if (b0 == 0x78 && (b0 * 256 + b1) % 31 == 0) {
                raw = new InflaterInputStream(raw);
            }
        } catch (IOException e) {
            throw new NbtException("Le fichier est abîmé (compression illisible).");
        }
        Nbt reader = new Nbt(raw, maxUnpackedBytes);
        try {
            int type = reader.in.readUnsignedByte();
            if (type != 10) {
                throw new NbtException("Ce fichier n'est pas un schéma Minecraft.");
            }
            reader.in.readUTF(); // root name, unused
            return reader.compound(0);
        } catch (EOFException e) {
            throw new NbtException("Le fichier est incomplet (coupé pendant l'envoi ?).");
        } catch (java.util.zip.ZipException e) {
            throw new NbtException("Le fichier est abîmé (compression illisible).");
        } catch (java.io.UTFDataFormatException e) {
            throw new NbtException("Le fichier est abîmé (texte illisible).");
        }
    }

    private Map<String, Object> compound(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new NbtException("Le fichier est abîmé (trop imbriqué).");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, payload(type, depth + 1));
        }
    }

    private Object payload(int type, int depth) throws IOException {
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                int n = length(1);
                byte[] a = new byte[n];
                in.readFully(a);
                yield a;
            }
            case 8 -> in.readUTF();
            case 9 -> list(depth);
            case 10 -> compound(depth);
            case 11 -> {
                int n = length(4);
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readInt();
                }
                yield a;
            }
            case 12 -> {
                int n = length(8);
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readLong();
                }
                yield a;
            }
            default -> throw new NbtException("Le fichier est abîmé (type de donnée inconnu " + type + ").");
        };
    }

    private List<Object> list(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new NbtException("Le fichier est abîmé (trop imbriqué).");
        }
        int type = in.readUnsignedByte();
        int n = in.readInt();
        if (n <= 0 || type == 0) {
            return new ArrayList<>(0);
        }
        // Every element takes at least one byte: refuse lengths the remaining budget cannot hold.
        if (n > counter.remaining()) {
            throw new NbtException("Le fichier est trop gros une fois décompressé.");
        }
        List<Object> out = new ArrayList<>(Math.min(n, 4096));
        for (int i = 0; i < n; i++) {
            out.add(payload(type, depth + 1));
        }
        return out;
    }

    /** Reads an array length and checks that the bytes can really follow. */
    private int length(int bytesPerElement) throws IOException {
        int n = in.readInt();
        if (n < 0) {
            throw new NbtException("Le fichier est abîmé (taille négative).");
        }
        if ((long) n * bytesPerElement > counter.remaining()) {
            throw new NbtException("Le fichier est trop gros une fois décompressé.");
        }
        return n;
    }

    // ------------------------------------------------------------------ typed access helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof List<?> l ? (List<Object>) l : null;
    }

    public static String string(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof String s ? s : null;
    }

    /** Any integer tag (byte, short, int, long) as a long, or {@code def}. Shorts are read as unsigned (schematic sizes). */
    public static long number(Map<String, Object> m, String key, long def) {
        Object o = m == null ? null : m.get(key);
        if (o instanceof Short s) {
            return s & 0xFFFF;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return def;
    }

    public static boolean has(Map<String, Object> m, String key) {
        return m != null && m.containsKey(key);
    }

    /** Counts bytes coming out of the decompressor and stops at the limit. */
    private static final class Limited extends FilterInputStream {
        private final long max;
        private long count;

        Limited(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        long remaining() {
            return Math.max(0, max - count);
        }

        private void add(long n) throws NbtException {
            count += n;
            if (count > max) {
                throw new NbtException("Le fichier est trop gros une fois décompressé.");
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            if (s > 0) {
                add(s);
            }
            return s;
        }
    }
}
