// Exports the contents of an unprotected Director movie or cast (.dir/.cst, as written by
// ProjectorRays) to open formats, so the game can be ported or modded without Director.
//
//   java scripts/extract/DirectorExtract.java <movie.dir|cast.cst> <outdir> [--probe]
//
// Container layout follows the RIFX format as documented by ProjectorRays and ScummVM:
// imap -> mmap (chunk table), KEY_ (which resources belong to which cast member),
// MCsL / CAS* (cast libraries and their member slots), CASt (member type, name, specific data).
// Chunk-table chunks use the file's byte order; Director's own chunks (CASt, CAS*, MCsL, ...)
// are always big-endian.

import java.io.*;
import java.nio.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;

public class DirectorExtract {

    // ---------------------------------------------------------------- container

    static final class Chunk {
        final int index; final String fourCC; final int offset; final int length;
        Chunk(int index, String fourCC, int offset, int length) {
            this.index = index; this.fourCC = fourCC; this.offset = offset; this.length = length;
        }
    }

    final byte[] file;
    final ByteOrder order;
    final String codec;
    final Map<Integer, Chunk> chunks = new LinkedHashMap<>();
    // owner section ID (a CASt, or 1024 for the movie) -> fourCC -> resource section IDs
    final Map<Integer, Map<String, List<Integer>>> resources = new HashMap<>();

    DirectorExtract(byte[] file) {
        this.file = file;
        String magic = new String(file, 0, 4, Charset.forName("ISO-8859-1"));
        if (magic.equals("XFIR")) order = ByteOrder.LITTLE_ENDIAN;
        else if (magic.equals("RIFX")) order = ByteOrder.BIG_ENDIAN;
        else throw new IllegalArgumentException("Not a RIFX file (magic " + magic + "); unprotect it with ProjectorRays first.");
        codec = fourCC(u32(8, order));
        if (codec.equals("FGDM") || codec.equals("FGDC"))
            throw new IllegalArgumentException("Compressed (Afterburner) file; unprotect it with ProjectorRays first.");
        readChunkTable();
        readKeyTable();
    }

    int u32(int pos, ByteOrder o) { return ByteBuffer.wrap(file, pos, 4).order(o).getInt(); }
    int u16(int pos, ByteOrder o) { return ByteBuffer.wrap(file, pos, 2).order(o).getShort() & 0xffff; }

    static String fourCC(int v) {
        return "" + (char) ((v >>> 24) & 0xff) + (char) ((v >>> 16) & 0xff) + (char) ((v >>> 8) & 0xff) + (char) (v & 0xff);
    }

    void readChunkTable() {
        // imap sits right after the 12-byte RIFX header.
        if (!fourCC(u32(12, order)).equals("imap")) throw new IllegalStateException("imap not found");
        int mmapOffset = u32(12 + 8 + 4, order);
        if (!fourCC(u32(mmapOffset, order)).equals("mmap")) throw new IllegalStateException("mmap not found");
        int base = mmapOffset + 8;
        int headerSize = u16(base, order);
        int entrySize = u16(base + 2, order);
        int used = u32(base + 8, order);
        for (int i = 0; i < used; i++) {
            int e = base + headerSize + i * entrySize;
            String cc = fourCC(u32(e, order));
            int len = u32(e + 4, order);
            int off = u32(e + 8, order);
            if (cc.equals("free") || cc.equals("junk")) continue;
            chunks.put(i, new Chunk(i, cc, off, len));
        }
    }

    void readKeyTable() {
        Chunk key = first("KEY*");
        if (key == null) return;
        int p = key.offset + 8;
        int propSize = u16(p, order);
        int keySize = u16(p + 2, order);
        int used = u32(p + 8, order);
        for (int i = 0; i < used; i++) {
            int e = p + propSize + i * keySize;
            int section = u32(e, order), owner = u32(e + 4, order);
            String cc = fourCC(u32(e + 8, order));
            resources.computeIfAbsent(owner, k -> new LinkedHashMap<>())
                     .computeIfAbsent(cc, k -> new ArrayList<>()).add(section);
        }
    }

    Chunk first(String cc) {
        for (Chunk c : chunks.values()) if (c.fourCC.equals(cc)) return c;
        return null;
    }

    /** Chunk payload (without the 8-byte fourCC/length header). */
    byte[] data(Chunk c) {
        int len = u32(c.offset + 4, order);
        return Arrays.copyOfRange(file, c.offset + 8, c.offset + 8 + len);
    }

    /** Resource sections of one type owned by a member (only ones present in the chunk table). */
    List<Integer> owned(int owner, String cc) {
        Map<String, List<Integer>> m = resources.get(owner);
        if (m == null) return List.of();
        List<Integer> out = new ArrayList<>();
        for (int s : m.getOrDefault(cc, List.of())) if (chunks.containsKey(s)) out.add(s);
        return out;
    }

    // ---------------------------------------------------------------- casts and members

    static final class CastLib {
        String name = "", path = ""; int minMember = 1, maxMember, id; int sectionOfCasStar = -1;
    }

    static final class Member {
        int section; int type; String name = ""; byte[] specific; byte[] info;
        int castLibIndex; int number;
    }

    static String pascal(byte[] b, int off) {
        if (off >= b.length) return "";
        int len = b[off] & 0xff;
        return new String(b, off + 1, Math.min(len, b.length - off - 1), Charset.forName("windows-1252"));
    }

    /** Director "list chunk": items addressed through an offset table at dataOffset. */
    static List<byte[]> listItems(byte[] b, int dataOffset) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        List<byte[]> items = new ArrayList<>();
        if (dataOffset + 2 > b.length) return items;
        int count = bb.getShort(dataOffset) & 0xffff;
        int[] offs = new int[count];
        for (int i = 0; i < count; i++) offs[i] = bb.getInt(dataOffset + 2 + i * 4);
        int itemsLen = bb.getInt(dataOffset + 2 + count * 4);
        int itemsBase = dataOffset + 2 + count * 4 + 4;
        for (int i = 0; i < count; i++) {
            int start = offs[i], end = (i + 1 < count) ? offs[i + 1] : itemsLen;
            if (start > end || itemsBase + end > b.length) { items.add(new byte[0]); continue; }
            items.add(Arrays.copyOfRange(b, itemsBase + start, itemsBase + end));
        }
        return items;
    }

    List<CastLib> castLibs = new ArrayList<>();
    List<Member> members = new ArrayList<>();

    void readCasts() {
        Chunk mcsl = first("MCsL");
        if (mcsl != null) {
            byte[] b = data(mcsl);
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
            int dataOffset = bb.getInt(0);
            int castCount = bb.getShort(6) & 0xffff;
            int itemsPerCast = bb.getShort(8) & 0xffff;
            List<byte[]> items = listItems(b, dataOffset);
            for (int i = 0; i < castCount; i++) {
                CastLib c = new CastLib();
                int base = i * itemsPerCast;
                if (itemsPerCast >= 1 && base + 1 < items.size()) c.name = pascal(items.get(base + 1), 0);
                if (itemsPerCast >= 2 && base + 2 < items.size()) c.path = pascal(items.get(base + 2), 0);
                if (itemsPerCast >= 4 && base + 4 < items.size() && items.get(base + 4).length >= 8) {
                    ByteBuffer ib = ByteBuffer.wrap(items.get(base + 4)).order(ByteOrder.BIG_ENDIAN);
                    c.minMember = ib.getShort(0) & 0xffff; c.maxMember = ib.getShort(2) & 0xffff; c.id = ib.getInt(4);
                }
                castLibs.add(c);
            }
        }
        // CAS* chunks: member slots of the internal casts; KEY_ maps each to its cast lib id.
        Map<Integer, Integer> casStarOwner = new HashMap<>();
        for (Map.Entry<Integer, Map<String, List<Integer>>> e : resources.entrySet())
            for (int s : e.getValue().getOrDefault("CAS*", List.of())) casStarOwner.put(s, e.getKey());
        int libIndex = 0;
        for (Chunk c : chunks.values()) {
            if (!c.fourCC.equals("CAS*")) continue;
            CastLib lib = null;
            Integer ownerId = casStarOwner.get(c.index);
            if (ownerId != null) for (CastLib l : castLibs) if (l.id == ownerId) lib = l;
            if (lib == null) {
                lib = new CastLib();
                lib.name = castLibs.isEmpty() ? "Internal" : "cast" + (libIndex + 1);
                castLibs.add(lib);
            }
            lib.sectionOfCasStar = c.index;
            byte[] b = data(c);
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i + 4 <= b.length; i += 4) {
                int section = bb.getInt(i);
                if (section <= 0 || !chunks.containsKey(section)) continue;
                Member m = readMember(section);
                m.castLibIndex = castLibs.indexOf(lib);
                m.number = lib.minMember + i / 4;
                members.add(m);
            }
            libIndex++;
        }
    }

    Member readMember(int section) {
        byte[] b = data(chunks.get(section));
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        Member m = new Member();
        m.section = section;
        m.type = bb.getInt(0);
        int infoLen = bb.getInt(4), specLen = bb.getInt(8);
        m.info = Arrays.copyOfRange(b, 12, 12 + infoLen);
        m.specific = Arrays.copyOfRange(b, 12 + infoLen, 12 + infoLen + specLen);
        if (infoLen >= 20) {
            int dataOffset = ByteBuffer.wrap(m.info).order(ByteOrder.BIG_ENDIAN).getInt(0);
            List<byte[]> items = listItems(m.info, dataOffset);
            if (items.size() > 1) m.name = pascal(items.get(1), 0);
        }
        return m;
    }

    // ---------------------------------------------------------------- bitmaps

    static final class BitmapInfo {
        int pitch, top, left, bottom, right, regX, regY, flags, bpp = 1, clutLib = -1, clutId;
        int width() { return right - left; }
        int height() { return bottom - top; }
    }

    static BitmapInfo bitmapInfo(byte[] spec) {
        ByteBuffer b = ByteBuffer.wrap(spec).order(ByteOrder.BIG_ENDIAN);
        BitmapInfo i = new BitmapInfo();
        int pitchWord = b.getShort(0) & 0xffff;
        i.pitch = pitchWord & 0x3fff;
        i.top = b.getShort(2); i.left = b.getShort(4); i.bottom = b.getShort(6); i.right = b.getShort(8);
        // bytes 10..17: bounding rect (unused for export)
        i.regY = b.getShort(18); i.regX = b.getShort(20);
        if ((pitchWord & 0x8000) != 0 && spec.length >= 28) {
            i.flags = spec[22] & 0xff;
            i.bpp = spec[23] & 0xff;
            i.clutLib = b.getShort(24);
            i.clutId = b.getShort(26);
        }
        return i;
    }

    /** BITD payload -> pitch*height bytes; Director compresses rows with a PackBits-style RLE. */
    static byte[] unpackBitd(byte[] data, int expected) {
        if (data.length >= expected) return Arrays.copyOf(data, expected);
        byte[] out = new byte[expected];
        int i = 0, o = 0;
        while (i < data.length && o < expected) {
            int n = data[i++] & 0xff;
            if (n < 0x80) {
                for (int k = 0; k <= n && i < data.length && o < expected; k++) out[o++] = data[i++];
            } else {
                int count = 257 - n;
                byte v = i < data.length ? data[i++] : 0;
                for (int k = 0; k < count && o < expected; k++) out[o++] = v;
            }
        }
        return out;
    }

    /** Decoded bitmap: palette indices for 1-8 bit, ARGB for 16/32 bit. */
    static final class Pixels {
        int width, height, bpp; int[] values; boolean indexed;
    }

    static Pixels decodeBitmap(BitmapInfo info, byte[] bitd) {
        int w = info.width(), h = info.height(), bpp = info.bpp, pitch = info.pitch;
        Pixels p = new Pixels();
        p.width = w; p.height = h; p.bpp = bpp; p.values = new int[w * h];
        if (w <= 0 || h <= 0) return p;
        byte[] d = unpackBitd(bitd, pitch * h);
        if (bpp <= 8) {
            p.indexed = true;
            for (int y = 0; y < h; y++) {
                int row = y * pitch;
                for (int x = 0; x < w; x++) {
                    int bit = x * bpp;
                    int byteVal = d[row + bit / 8] & 0xff;
                    int shift = 8 - bpp - (bit % 8);
                    p.values[y * w + x] = (byteVal >> shift) & ((1 << bpp) - 1);
                }
            }
        } else if (bpp == 16) {
            // Each row holds the high bytes of every pixel, then the low bytes; xRRRRRGG GGGBBBBB.
            boolean planar = bitd.length < pitch * h;
            for (int y = 0; y < h; y++) {
                int row = y * pitch;
                for (int x = 0; x < w; x++) {
                    int hi, lo;
                    if (planar) { hi = d[row + x] & 0xff; lo = d[row + w + x] & 0xff; }
                    else { hi = d[row + 2 * x] & 0xff; lo = d[row + 2 * x + 1] & 0xff; }
                    int v = (hi << 8) | lo;
                    int r = (v >> 10) & 31, g = (v >> 5) & 31, bl = v & 31;
                    p.values[y * w + x] = 0xff000000 | (expand5(r) << 16) | (expand5(g) << 8) | expand5(bl);
                }
            }
        } else if (bpp == 32) {
            // Compressed rows are planar (A, R, G, B runs of width bytes); raw rows are ARGB.
            boolean planar = bitd.length < pitch * h;
            for (int y = 0; y < h; y++) {
                int row = y * pitch;
                for (int x = 0; x < w; x++) {
                    int a, r, g, bl;
                    if (planar) { a = d[row + x] & 0xff; r = d[row + w + x] & 0xff; g = d[row + 2 * w + x] & 0xff; bl = d[row + 3 * w + x] & 0xff; }
                    else { a = d[row + 4 * x] & 0xff; r = d[row + 4 * x + 1] & 0xff; g = d[row + 4 * x + 2] & 0xff; bl = d[row + 4 * x + 3] & 0xff; }
                    p.values[y * w + x] = (a << 24) | (r << 16) | (g << 8) | bl;
                }
            }
        }
        return p;
    }

    static int expand5(int v) { return (v << 3) | (v >> 2); }

    // ---------------------------------------------------------------- palettes

    /** CLUT payload: 6 bytes per entry (16-bit R, G, B; Director keeps the high byte). */
    static int[] readClut(byte[] data) {
        int n = data.length / 6;
        int[] rgb = new int[n];
        for (int i = 0; i < n; i++) {
            int r = data[i * 6] & 0xff, g = data[i * 6 + 2] & 0xff, b = data[i * 6 + 4] & 0xff;
            rgb[i] = (r << 16) | (g << 8) | b;
        }
        return rgb;
    }

    /** Built-in palettes, by Director's id (-1 System Mac, -3 Grayscale, ...). */
    static int[] systemPalette(int id) {
        int[] p = new int[256];
        switch (id) {
            case -3: // Grayscale: index 0 white -> 255 black
                for (int i = 0; i < 256; i++) { int v = 255 - i; p[i] = (v << 16) | (v << 8) | v; }
                return p;
            default: { // System Mac (also the fallback): 6x6x6 cube without black, four ramps, black
                int i = 0;
                int[] steps = {0xff, 0xcc, 0x99, 0x66, 0x33, 0x00};
                for (int r : steps) for (int g : steps) for (int b : steps) if (i < 215) p[i++] = (r << 16) | (g << 8) | b;
                int[] ramp = {0xee, 0xdd, 0xbb, 0xaa, 0x88, 0x77, 0x55, 0x44, 0x22, 0x11};
                for (int v : ramp) p[i++] = v << 16;
                for (int v : ramp) p[i++] = v << 8;
                for (int v : ramp) p[i++] = v;
                for (int v : ramp) p[i++] = (v << 16) | (v << 8) | v;
                p[255] = 0;
                return p;
            }
        }
    }

    static String systemPaletteName(int id) {
        switch (id) {
            case -1: return "systemMac"; case -2: return "rainbow"; case -3: return "grayscale";
            case -4: return "pastels"; case -5: return "vivid"; case -6: return "ntsc"; case -7: return "metallic";
            case -101: return "systemWin"; case -102: return "systemWinDir4";
            default: return "system" + id;
        }
    }

    // ---------------------------------------------------------------- export

    static final String[] TYPE_NAMES = {"null", "bitmap", "filmLoop", "text", "palette", "picture", "sound",
        "button", "shape", "movie", "digitalVideo", "script", "richText", "ole", "transition", "xtra"};

    static String typeName(int t) { return t >= 0 && t < TYPE_NAMES.length ? TYPE_NAMES[t] : "type" + t; }

    static String safe(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        return r.isEmpty() ? "unnamed" : r;
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\r') b.append("\\r"); else if (c == '\n') b.append("\\n"); else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    Member findMember(int castLibIndex, int number) {
        for (Member m : members) if (m.castLibIndex == castLibIndex && m.number == number) return m;
        return null;
    }

    /** Palette for a bitmap: a palette member in a cast, or a built-in one. */
    int[] resolvePalette(Member owner, BitmapInfo bi, StringBuilder name) {
        if (bi.clutId > 0) {
            int lib = bi.clutLib == -1 ? owner.castLibIndex : bi.clutLib - 1;
            Member pm = findMember(lib, bi.clutId);
            if (pm != null) {
                List<Integer> clut = owned(pm.section, "CLUT");
                if (!clut.isEmpty()) {
                    name.append(castLibs.get(pm.castLibIndex).name).append('#').append(pm.number).append(' ').append(pm.name);
                    return readClut(data(chunks.get(clut.get(0))));
                }
            }
            name.append("missing(").append(bi.clutLib).append('/').append(bi.clutId).append(") systemMac");
            return systemPalette(-1);
        }
        int id = bi.clutId - 1;
        name.append(systemPaletteName(id));
        return systemPalette(id);
    }

    static java.awt.image.BufferedImage toImage(Pixels px, int[] palette) {
        int w = Math.max(px.width, 1), h = Math.max(px.height, 1);
        if (px.indexed) {
            int n = 1 << px.bpp;
            byte[] r = new byte[n], g = new byte[n], b = new byte[n];
            for (int i = 0; i < n; i++) {
                int c = i < palette.length ? palette[i] : 0;
                r[i] = (byte) (c >> 16); g[i] = (byte) (c >> 8); b[i] = (byte) c;
            }
            java.awt.image.IndexColorModel cm = new java.awt.image.IndexColorModel(px.bpp == 2 || px.bpp == 4 || px.bpp == 1 ? px.bpp : 8, n, r, g, b);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h,
                px.bpp <= 4 ? java.awt.image.BufferedImage.TYPE_BYTE_BINARY : java.awt.image.BufferedImage.TYPE_BYTE_INDEXED, cm);
            java.awt.image.WritableRaster ras = img.getRaster();
            for (int y = 0; y < px.height; y++) for (int x = 0; x < px.width; x++) ras.setSample(x, y, 0, px.values[y * px.width + x]);
            return img;
        }
        boolean anyAlpha = false;
        for (int v : px.values) if ((v >>> 24) != 0) { anyAlpha = true; break; }
        boolean hasAlpha = false;
        if (anyAlpha) for (int v : px.values) if ((v >>> 24) != 0xff) { hasAlpha = true; break; }
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h,
            hasAlpha ? java.awt.image.BufferedImage.TYPE_INT_ARGB : java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px.height; y++) for (int x = 0; x < px.width; x++) {
            int v = px.values[y * px.width + x];
            img.setRGB(x, y, hasAlpha ? v : (0xff000000 | v));
        }
        return img;
    }

    /** Writes assets and a cast.json per cast library under outDir. */
    void export(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        int counts[] = new int[32];
        for (int li = 0; li < castLibs.size(); li++) {
            CastLib lib = castLibs.get(li);
            List<Member> libMembers = new ArrayList<>();
            for (Member m : members) if (m.castLibIndex == li) libMembers.add(m);
            if (libMembers.isEmpty()) continue;
            Path dir = outDir.resolve(safe(lib.name));
            Files.createDirectories(dir);
            StringBuilder js = new StringBuilder();
            js.append("{\n  \"castLib\": ").append(json(lib.name)).append(",\n  \"fileName\": ").append(json(lib.path))
              .append(",\n  \"members\": [\n");
            boolean firstMember = true;
            for (Member m : libMembers) {
                String base = String.format("%04d_%s", m.number, safe(m.name));
                StringBuilder extra = new StringBuilder();
                List<String> files = new ArrayList<>();
                try {
                    switch (m.type) {
                        case 1: exportBitmap(m, dir, base, extra, files); break;
                        case 4: exportPalette(m, dir, base, files); break;
                        case 6: exportSound(m, dir, base, extra, files); break;
                        case 3: case 7: case 12: exportText(m, dir, base, files); break;
                        default: break;
                    }
                } catch (Exception e) {
                    extra.append(", \"error\": ").append(json(e.toString()));
                }
                counts[Math.min(m.type, 31)]++;
                if (!firstMember) js.append(",\n");
                firstMember = false;
                js.append("    {\"number\": ").append(m.number).append(", \"name\": ").append(json(m.name))
                  .append(", \"type\": ").append(json(typeName(m.type)));
                Map<String, List<Integer>> r = resources.get(m.section);
                if (r != null && !r.isEmpty()) js.append(", \"chunks\": ").append(json(String.join(" ", r.keySet())));
                js.append(extra);
                if (!files.isEmpty()) {
                    js.append(", \"files\": [");
                    for (int i = 0; i < files.size(); i++) js.append(i > 0 ? ", " : "").append(json(files.get(i)));
                    js.append("]");
                }
                js.append("}");
            }
            js.append("\n  ]\n}\n");
            Files.writeString(dir.resolve("cast.json"), js.toString());
        }
        List<Object[]> labels = frameLabels();
        if (!labels.isEmpty()) {
            StringBuilder lj = new StringBuilder("[\n");
            for (int i = 0; i < labels.size(); i++)
                lj.append("  {\"frame\": ").append(labels.get(i)[0]).append(", \"label\": ").append(json((String) labels.get(i)[1])).append(i + 1 < labels.size() ? "},\n" : "}\n");
            Files.writeString(outDir.resolve("labels.json"), lj.append("]\n").toString());
        }
        StringBuilder summary = new StringBuilder();
        if (!labels.isEmpty()) summary.append(labels.size()).append(" frame labels; ");
        for (int t = 0; t < counts.length; t++) if (counts[t] > 0) summary.append(typeName(t)).append(' ').append(counts[t]).append("  ");
        System.out.println("  " + members.size() + " members: " + summary.toString().trim());
    }

    void exportBitmap(Member m, Path dir, String base, StringBuilder extra, List<String> files) throws IOException {
        BitmapInfo bi = bitmapInfo(m.specific);
        StringBuilder palName = new StringBuilder();
        int[] palette = bi.bpp <= 8 ? resolvePalette(m, bi, palName) : null;
        extra.append(", \"width\": ").append(bi.width()).append(", \"height\": ").append(bi.height())
             .append(", \"regPoint\": [").append(bi.regX).append(", ").append(bi.regY).append("]")
             .append(", \"depth\": ").append(bi.bpp);
        if (palette != null) extra.append(", \"palette\": ").append(json(palName.toString()));
        List<Integer> bitd = owned(m.section, "BITD");
        if (bitd.isEmpty() || bi.width() <= 0 || bi.height() <= 0) return;
        Pixels px = decodeBitmap(bi, data(chunks.get(bitd.get(0))));
        if (bi.bpp == 1) palette = new int[] {0xffffff, 0x000000}; // 1-bit: 0 white, 1 black
        String file = base + ".png";
        javax.imageio.ImageIO.write(toImage(px, palette), "png", dir.resolve(file).toFile());
        files.add(file);
    }

    void exportPalette(Member m, Path dir, String base, List<String> files) throws IOException {
        List<Integer> clut = owned(m.section, "CLUT");
        if (clut.isEmpty()) return;
        int[] pal = readClut(data(chunks.get(clut.get(0))));
        // Adobe Color Table (.act): 256 RGB triplets, usable in most paint programs.
        byte[] act = new byte[768];
        for (int i = 0; i < Math.min(256, pal.length); i++) {
            act[i * 3] = (byte) (pal[i] >> 16); act[i * 3 + 1] = (byte) (pal[i] >> 8); act[i * 3 + 2] = (byte) pal[i];
        }
        Files.write(dir.resolve(base + ".act"), act);
        StringBuilder js = new StringBuilder("[");
        for (int i = 0; i < pal.length; i++) js.append(i > 0 ? ", " : "").append(String.format("\"#%06x\"", pal[i]));
        Files.writeString(dir.resolve(base + ".palette.json"), js.append("]\n").toString());
        files.add(base + ".act");
        files.add(base + ".palette.json");
    }

    // ---------------------------------------------------------------- sounds

    static final class Sound {
        int rate, channels = 1, bits = 8, frames; int encoding; byte[] pcm; // pcm: WAV byte order
    }

    /**
     * Mac 'snd ' resource (format 1 or 2) with a bufferCmd/soundCmd pointing at a sampled-sound
     * header: standard (encode 0x00, 8-bit mono), extended (0xFF) or compressed (0xFE).
     */
    static Sound parseSnd(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        int p = 0;
        int format = bb.getShort(p) & 0xffff; p += 2;
        if (format == 1) {
            int dataFormats = bb.getShort(p) & 0xffff; p += 2;
            p += dataFormats * 6; // dataType u16 + initOption u32
        } else {
            p += 2; // reference count
        }
        int commands = bb.getShort(p) & 0xffff; p += 2;
        int header = -1;
        for (int i = 0; i < commands; i++) {
            int cmd = bb.getShort(p) & 0xffff;
            int param2 = bb.getInt(p + 4);
            if ((cmd & 0x7fff) == 0x51 || (cmd & 0x7fff) == 0x50) header = param2; // bufferCmd / soundCmd
            p += 8;
        }
        if (header < 0) throw new IllegalArgumentException("no sound command");
        Sound s = new Sound();
        int lenOrChannels = bb.getInt(header + 4);
        s.rate = (int) ((bb.getInt(header + 8) & 0xffffffffL) >>> 16);
        s.encoding = b[header + 20] & 0xff;
        int dataStart;
        if (s.encoding == 0x00) {
            s.frames = lenOrChannels; s.channels = 1; s.bits = 8;
            dataStart = header + 22;
        } else if (s.encoding == 0xff) {
            s.channels = lenOrChannels;
            s.frames = bb.getInt(header + 22);
            s.bits = bb.getShort(header + 48) & 0xffff;
            dataStart = header + 64;
        } else {
            throw new IllegalArgumentException("compressed sound (encode 0x" + Integer.toHexString(s.encoding) + ")");
        }
        int bytes = s.frames * s.channels * (s.bits / 8);
        bytes = Math.min(bytes, b.length - dataStart);
        s.pcm = Arrays.copyOfRange(b, dataStart, dataStart + bytes);
        if (s.bits == 16) for (int i = 0; i + 1 < s.pcm.length; i += 2) { byte t = s.pcm[i]; s.pcm[i] = s.pcm[i + 1]; s.pcm[i + 1] = t; }
        return s;
    }

    static byte[] wav(Sound s) {
        int blockAlign = s.channels * s.bits / 8;
        ByteBuffer w = ByteBuffer.allocate(44 + s.pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        w.put("RIFF".getBytes()).putInt(36 + s.pcm.length).put("WAVE".getBytes());
        w.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) s.channels)
         .putInt(s.rate).putInt(s.rate * blockAlign).putShort((short) blockAlign).putShort((short) s.bits);
        w.put("data".getBytes()).putInt(s.pcm.length).put(s.pcm);
        return w.array();
    }

    void exportSound(Member m, Path dir, String base, StringBuilder extra, List<String> files) throws IOException {
        List<Integer> snd = owned(m.section, "snd ");
        if (!snd.isEmpty()) {
            Sound s = parseSnd(data(chunks.get(snd.get(0))));
            extra.append(", \"sampleRate\": ").append(s.rate).append(", \"channels\": ").append(s.channels)
                 .append(", \"bits\": ").append(s.bits).append(", \"frames\": ").append(s.frames);
            Files.write(dir.resolve(base + ".wav"), wav(s));
            files.add(base + ".wav");
            return;
        }
        List<Integer> media = owned(m.section, "ediM");
        if (!media.isEmpty()) {
            // Compressed (Shockwave Audio / MP3) sound kept as stored.
            byte[] b = data(chunks.get(media.get(0)));
            Files.write(dir.resolve(base + ".ediM.bin"), b);
            files.add(base + ".ediM.bin");
            extra.append(", \"compressed\": true");
        }
    }

    // ---------------------------------------------------------------- labels and text

    /** VWLB: label count, then (count+1) (frame, text offset) pairs, then the label text. */
    List<Object[]> frameLabels() {
        List<Object[]> out = new ArrayList<>();
        Chunk c = first("VWLB");
        if (c == null) return out;
        byte[] b = data(c);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        int n = bb.getShort(0) & 0xffff;
        int textBase = 2 + (n + 1) * 4;
        for (int i = 0; i < n; i++) {
            int frame = bb.getShort(2 + i * 4) & 0xffff;
            int start = bb.getShort(4 + i * 4) & 0xffff, end = bb.getShort(8 + i * 4) & 0xffff;
            String label = new String(b, textBase + start, Math.max(0, end - start), Charset.forName("windows-1252"));
            out.add(new Object[] {frame, label});
        }
        return out;
    }

    /** STXT: header length, text length, style data length; then Mac Roman text and style runs. */
    static String stxt(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        int headerLen = bb.getInt(0), textLen = bb.getInt(4);
        String text = new String(b, headerLen, Math.min(textLen, b.length - headerLen), Charset.forName("x-MacRoman"));
        return text.replace("\r", "\n");
    }

    void exportText(Member m, Path dir, String base, List<String> files) throws IOException {
        for (int s : owned(m.section, "STXT")) {
            Files.writeString(dir.resolve(base + ".txt"), stxt(data(chunks.get(s))));
            files.add(base + ".txt");
            return;
        }
        for (int s : owned(m.section, "XMED")) {
            // Styled text (Director 7+ "text" member): kept as stored.
            Files.write(dir.resolve(base + ".xmed.bin"), data(chunks.get(s)));
            files.add(base + ".xmed.bin");
            return;
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------------------------------------------------------------- entry point

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java DirectorExtract.java <movie.dir|cast.cst> <outdir> [--probe]");
            System.exit(2);
        }
        DirectorExtract d = new DirectorExtract(Files.readAllBytes(Paths.get(args[0])));
        d.readCasts();
        if (args.length > 3 && args[2].equals("--pixel")) {
            // --pixel <member name> x,y ...: decoded values, for checking against a player.
            for (Member m : d.members) {
                if (m.type != 1 || !m.name.equals(args[3])) continue;
                BitmapInfo bi = bitmapInfo(m.specific);
                List<Integer> bitd = d.owned(m.section, "BITD");
                Pixels px = decodeBitmap(bi, bitd.isEmpty() ? new byte[0] : d.data(d.chunks.get(bitd.get(0))));
                StringBuilder sb = new StringBuilder(m.name + " " + bi.width() + "x" + bi.height() + " bpp " + bi.bpp + " pitch " + bi.pitch + " reg " + bi.regX + "," + bi.regY + " clut " + bi.clutLib + "/" + bi.clutId + ":");
                for (int k = 4; k < args.length; k++) {
                    String[] xy = args[k].split(",");
                    int v = px.values[Integer.parseInt(xy[1]) * px.width + Integer.parseInt(xy[0])];
                    sb.append(' ').append(args[k]).append('=');
                    sb.append(px.indexed ? "color(" + v + ")" : "rgb(" + ((v >> 16) & 255) + ", " + ((v >> 8) & 255) + ", " + (v & 255) + ") a" + ((v >>> 24) & 255));
                }
                System.out.println(sb);
            }
            return;
        }
        if (args.length == 2) {
            d.export(Paths.get(args[1]));
            return;
        }
        if (args.length > 2 && args[2].equals("--probe")) {
            System.out.println("codec " + d.codec + ", " + d.chunks.size() + " chunks, " + d.members.size() + " members");
            for (CastLib l : d.castLibs) System.out.println("castLib '" + l.name + "' members " + l.minMember + ".." + l.maxMember + " id " + l.id);
            for (Member m : d.members) {
                StringBuilder res = new StringBuilder();
                Map<String, List<Integer>> r = d.resources.get(m.section);
                if (r != null) for (String k : r.keySet()) res.append(k).append(' ');
                System.out.println(d.castLibs.get(m.castLibIndex).name + "#" + m.number + " type " + m.type + " '" + m.name + "' res[" + res.toString().trim() + "] spec " + hex(m.specific));
            }
        }
    }
}
