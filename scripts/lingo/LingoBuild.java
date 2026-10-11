// Compiles a movie's Lingo source back into the movie: the edited-scripts half of a Director build.
//
//   java LingoBuild <in.dir|in.cst> <decompiled folder> <out.dir|out.cst>
//
// Every script that has a .ls file in the folder is compiled (LingoCompiler) and written into
// its Lscr chunk; new names are appended to the Lnam chunk. Everything else in the file is
// copied as is, so a movie whose scripts weren't edited comes out byte-identical.

import java.io.*;
import java.nio.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;

public class LingoBuild {

    static final Charset MAC = Charset.forName("x-MacRoman");
    static final Charset SRC = Charset.forName("windows-1252");

    /** Event handlers Director also lists in a script's handler-vector table, by slot. */
    static final Map<String, Integer> EVENT_SLOTS = new HashMap<>();
    static {
        String[][] e = {{"mouseDown", "0"}, {"mouseUp", "1"}, {"keyDown", "2"}, {"keyUp", "3"}, {"prepareFrame", "5"},
            {"mouseEnter", "7"}, {"mouseLeave", "8"}, {"stopMovie", "12"}, {"exitFrame", "15"}};
        for (String[] x : e) EVENT_SLOTS.put(x[0].toLowerCase(), Integer.parseInt(x[1]));
    }

    public static void main(String[] a) throws Exception {
        byte[] file = Files.readAllBytes(Paths.get(a[0]));
        DirectorExtract d = new DirectorExtract(file);
        d.readCasts();
        Path dir = Paths.get(a[1]);
        Map<Integer, Integer> scriptIds = LingoRoundTrip.scriptIds(d);
        Map<Integer, byte[]> replaced = new HashMap<>();
        int compiled = 0, changed = 0;
        for (DirectorExtract.Chunk c : d.chunks.values()) {
            boolean capitalX = c.fourCC.equals("LctX");
            if (!c.fourCC.equals("Lctx") && !capitalX) continue;
            LingoScript.Context ctx = LingoScript.readContext(d.data(c));
            if (!d.chunks.containsKey(ctx.lnamSection)) continue;
            List<String> nameList = LingoScript.readNames(d.data(d.chunks.get(ctx.lnamSection)));
            LingoCompiler.Names names = new LingoCompiler.Names(nameList);
            for (int entry = 1; entry <= ctx.scriptSections.size(); entry++) {
                int sec = ctx.scriptSections.get(entry - 1);
                if (sec <= 0 || !d.chunks.containsKey(sec)) continue;
                byte[] old = d.data(d.chunks.get(sec));
                LingoScript.Script orig = LingoScript.readScript(old, capitalX);
                Integer live = scriptIds.get(orig.castID);
                if (live != null && live != entry) continue;
                Path src = LingoRoundTrip.source(d, dir, orig.castID);
                if (src == null) continue;
                names.preferred = LingoRoundTrip.usedNames(orig);
                names.newFrom = LingoRoundTrip.newFrom(orig, nameList.size());
                LingoCompiler.CompiledScript comp;
                try {
                    comp = LingoCompiler.compile(LingoCompiler.parse(new String(Files.readAllBytes(src), SRC)), names, !capitalX);
                } catch (RuntimeException e) {
                    throw new IllegalStateException(src.getFileName() + ": " + e.getMessage(), e);
                }
                List<Integer> pads = new ArrayList<>();
                byte[] now = writeScript(orig, comp, capitalX, pads);
                compiled++;
                if (!sameButPadding(old, now, pads)) {
                    replaced.put(sec, now); changed++;
                    System.out.println("    compiled your edits: " + src.getFileName());
                }
            }
            if (names.list.size() > nameList.size()) replaced.put(ctx.lnamSection, writeNames(names.list));
        }
        byte[] out = replaced.isEmpty() ? file : rewrite(d, file, replaced);
        Files.createDirectories(Paths.get(a[2]).toAbsolutePath().getParent());
        Files.write(Paths.get(a[2]), out);
        if (compiled > 0) System.out.println("  " + Paths.get(a[0]).getFileName() + ": " + compiled + " scripts compiled, " + changed + " changed");
    }

    // ------------------------------------------------------------------ Lnam

    static byte[] writeNames(List<String> names) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (String n : names) {
            byte[] b = n.getBytes(MAC);
            body.write(b.length);
            body.write(b, 0, b.length);
        }
        ByteBuffer h = ByteBuffer.allocate(20);
        h.putInt(8, body.size()).putInt(12, body.size()).putShort(16, (short) 20).putShort(18, (short) names.size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(h.array(), 0, 20);
        out.write(body.toByteArray(), 0, body.size());
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ Lscr

    /** Growable big-endian buffer with absolute patching. */
    static final class Buf {
        byte[] b = new byte[256]; int n;
        void ensure(int k) { if (n + k > b.length) b = Arrays.copyOf(b, Math.max(b.length * 2, n + k)); }
        void u8(int v) { ensure(1); b[n++] = (byte) v; }
        void u16(int v) { ensure(2); b[n++] = (byte) (v >> 8); b[n++] = (byte) v; }
        void u32(int v) { ensure(4); for (int s = 24; s >= 0; s -= 8) b[n++] = (byte) (v >> s); }
        void bytes(byte[] x) { ensure(x.length); System.arraycopy(x, 0, b, n, x.length); n += x.length; }
        List<Integer> pads = new ArrayList<>();
        void pad() { if ((n & 1) != 0) { pads.add(n); u8(0); } }
        void put16(int at, int v) { b[at] = (byte) (v >> 8); b[at + 1] = (byte) v; }
        void put32(int at, int v) { for (int i = 0; i < 4; i++) b[at + i] = (byte) (v >> (24 - 8 * i)); }
        byte[] done() { return Arrays.copyOf(b, n); }
    }

    /**
     * Serializes a compiled script in the original's chunk layout. Header fields the compiler
     * doesn't produce (script number, flags, cast id, factory name) come from the original, and
     * so do each unchanged handler's source position and line table.
     */
    static byte[] writeScript(LingoScript.Script orig, LingoCompiler.CompiledScript comp, boolean capitalX) { return writeScript(orig, comp, capitalX, null); }

    /** padsOut, if given, receives the positions of padding bytes. */
    static byte[] writeScript(LingoScript.Script orig, LingoCompiler.CompiledScript comp, boolean capitalX, List<Integer> padsOut) {
        int headerLen = ((orig.raw[16] & 0xff) << 8) | (orig.raw[17] & 0xff);
        int rec = capitalX ? 46 : 42;
        Buf w = new Buf();
        w.bytes(Arrays.copyOf(orig.raw, headerLen));

        int propOffset = w.n;
        for (int id : comp.propertyNames) w.u16(id);
        int globOffset = w.n;
        for (int id : comp.globalNames) w.u16(id);
        int handlerOffset = w.n;
        int nh = comp.handlers.size();
        w.ensure(nh * rec); w.n += nh * rec;

        // handler-vector table: event handlers by slot
        int[] vectorPos = new int[nh];
        int vectorCount = 0, vectorFlags = 0;
        for (int i = 0; i < nh; i++) {
            Integer slot = EVENT_SLOTS.get(comp.handlers.get(i).name.toLowerCase());
            vectorPos[i] = slot == null ? -1 : slot;
            if (slot != null) { vectorCount = Math.max(vectorCount, slot + 1); vectorFlags |= 1 << slot; }
        }

        for (int i = 0; i < nh; i++) {
            LingoCompiler.CompiledHandler h = comp.handlers.get(i);
            LingoScript.Handler o = original(orig, h);
            boolean same = o != null && Arrays.equals(o.bytecode, h.bytecode);
            int codeOffset = w.n;
            w.bytes(h.bytecode); w.pad();
            int argOffset = w.n;
            for (int id : h.argNames) w.u16(id);
            int localOffset = w.n;
            for (int id : h.localNames) w.u16(id);
            int hGlobOffset = w.n;
            for (int id : h.globalNames) w.u16(id);
            int lineOffset = w.n;
            byte[] lines = same ? o.lineTable : new byte[0];
            w.bytes(lines); w.pad();

            int p = handlerOffset + i * rec;
            w.put16(p, h.nameId);
            w.put16(p + 2, vectorPos[i]);
            w.put32(p + 4, h.bytecode.length);
            w.put32(p + 8, codeOffset);
            w.put16(p + 12, h.argNames.size()); w.put32(p + 14, argOffset);
            w.put16(p + 18, h.localNames.size()); w.put32(p + 20, localOffset);
            w.put16(p + 24, h.globalNames.size()); w.put32(p + 26, hGlobOffset);
            // where the handler started in the original source text (kept when the code is unchanged)
            w.put32(p + 30, same ? o.unknown1 : 0);
            w.put16(p + 34, same ? o.unknown2 : 0);
            w.put16(p + 36, lines.length);
            w.put32(p + 38, lineOffset);
            if (capitalX) w.put32(p + 42, same ? o.stackHeight : stackHeight(h.bytecode));
        }

        int litOffset = w.n;
        w.ensure(comp.literals.size() * 8); w.n += comp.literals.size() * 8;
        int litDataOffset = w.n;
        for (int i = 0; i < comp.literals.size(); i++) {
            LingoScript.Literal l = comp.literals.get(i);
            int p = litOffset + i * 8;
            w.put32(p, l.type);
            if (l.type == 4) { w.put32(p + 4, l.intValue); continue; }
            w.put32(p + 4, w.n - litDataOffset);
            if (l.type == 1) {
                byte[] s = l.string.getBytes(MAC);
                w.u32(s.length + 1); w.bytes(s); w.u8(0);
            } else if (l.type == 9) {
                w.u32(8);
                long bits = Double.doubleToLongBits(l.floatValue);
                w.u32((int) (bits >>> 32)); w.u32((int) bits);
            } else throw new IllegalStateException("literal type " + l.type);
            w.pad();
        }
        int litDataLen = w.n - litDataOffset;

        int vectorOffset = w.n;
        for (int s = 0; s < vectorCount; s++) {
            int idx = -1;
            for (int i = 0; i < nh; i++) if (vectorPos[i] == s) idx = i;
            w.u16(idx);
        }

        w.put32(8, w.n); w.put32(12, w.n);
        w.put16(50, vectorCount); w.put32(52, vectorOffset); w.put32(56, vectorFlags);
        w.put16(60, comp.propertyNames.size()); w.put32(62, propOffset);
        w.put16(66, comp.globalNames.size()); w.put32(68, globOffset);
        w.put16(72, nh); w.put32(74, handlerOffset);
        w.put16(78, comp.literals.size()); w.put32(80, litOffset);
        w.put32(84, litDataLen); w.put32(88, litDataOffset);
        if (padsOut != null) padsOut.addAll(w.pads);
        return w.done();
    }

    /** Equal except in padding bytes (Director leaves whatever was in memory there). */
    static boolean sameButPadding(byte[] old, byte[] now, List<Integer> pads) {
        if (old.length != now.length) return false;
        Set<Integer> p = new HashSet<>(pads);
        for (int i = 0; i < old.length; i++) if (old[i] != now[i] && !p.contains(i)) return false;
        return true;
    }

    static LingoScript.Handler original(LingoScript.Script orig, LingoCompiler.CompiledHandler h) {
        for (LingoScript.Handler o : orig.handlers) if (o.nameId == h.nameId) return o;
        return null;
    }

    /** Upper bound on a handler's stack depth (Director 8.5+ records it); straight-line simulation. */
    static int stackHeight(byte[] code) {
        int depth = 0, max = 0;
        Deque<Boolean> arglists = new ArrayDeque<>(); // pending argument lists: true = the call returns a value
        for (int[] ins : LingoRoundTrip.decode(code)) {
            int op = ins[0], base = op >= 0x40 ? op % 0x40 : -1, arg = ins[1];
            int d = 0;
            if (op < 0x40) {
                if (op == 0x03) d = 1;                                  // pushzero
                else if (op >= 0x04 && op <= 0x08 || op >= 0x0a && op <= 0x13 || op == 0x15 || op == 0x16) d = -1;
                else if (op == 0x1c) d = -1;                            // starttell
            } else switch (base) {
                case 0x01: case 0x04: case 0x05: case 0x08: case 0x09: case 0x0a: case 0x0b: case 0x0c:
                case 0x1f: case 0x24: case 0x2e: case 0x2f: case 0x31: d = 1; break;   // pushes, gets, peek
                case 0x02: case 0x03: arglists.push(base == 0x03); d = 1 - arg; break; // pusharglist[noret]
                case 0x16: case 0x17: case 0x27: case 0x23: case 0x26:                 // calls
                    d = (arglists.isEmpty() || arglists.pop()) ? 0 : -1; break;
                case 0x0e: case 0x0f: case 0x10: case 0x11: case 0x12: case 0x15: case 0x20: d = -1; break;
                case 0x22: d = -2; break;                               // setobjprop
                case 0x25: d = -arg; break;                             // pop
                case 0x19: d = -2; break;                               // put
                case 0x1a: d = -10; break;                              // putchunk
                case 0x1d: d = -2; break;                               // set
                default: d = 0;
            }
            depth += d;
            max = Math.max(max, depth);
        }
        return max;
    }

    // ------------------------------------------------------------------ container

    /**
     * Writes the file with some chunks' data replaced. Chunks keep their order and the gaps
     * between them; later ones move by the change in size, and the memory map follows.
     */
    static byte[] rewrite(DirectorExtract d, byte[] file, Map<Integer, byte[]> replaced) {
        ByteOrder o = d.order;
        int mmapOffset = d.u32(12 + 8 + 4, o);
        int base = mmapOffset + 8;
        int headerSize = d.u16(base, o), entrySize = d.u16(base + 2, o), used = d.u32(base + 8, o);

        // every chunk the map points at, by file position
        TreeMap<Integer, Integer> sizeAt = new TreeMap<>(); // offset -> new size (header + data); padding counts as gap
        TreeMap<Integer, Integer> oldSizeAt = new TreeMap<>();
        Map<Integer, byte[]> dataAt = new HashMap<>();
        for (int i = 0; i < used; i++) {
            int e = base + headerSize + i * entrySize;
            String cc = DirectorExtract.fourCC(d.u32(e, o));
            int off = d.u32(e + 8, o);
            if (cc.equals("free") || cc.equals("junk") || off <= 0 || off >= file.length) continue;
            if (cc.equals("RIFX") || cc.equals("XFIR")) continue;
            int len = d.u32(off + 4, o);
            int total = 8 + len;
            oldSizeAt.put(off, total);
            if (replaced.containsKey(i)) {
                byte[] nd = replaced.get(i);
                dataAt.put(off, nd);
                sizeAt.put(off, 8 + nd.length);
            } else sizeAt.put(off, total);
        }
        // new position of every old offset
        TreeMap<Integer, Integer> shift = new TreeMap<>();
        int delta = 0;
        for (Map.Entry<Integer, Integer> c : oldSizeAt.entrySet()) {
            shift.put(c.getKey(), delta);
            delta += sizeAt.get(c.getKey()) - c.getValue();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + Math.max(0, delta));
        int pos = 0;
        for (Map.Entry<Integer, Integer> c : oldSizeAt.entrySet()) {
            int off = c.getKey();
            out.write(file, pos, off - pos); // whatever lies between chunks
            byte[] nd = dataAt.get(off);
            if (nd == null) out.write(file, off, c.getValue());
            else {
                ByteBuffer h = ByteBuffer.allocate(8).order(o);
                h.put(file, off, 4);
                h.putInt(4, nd.length);
                out.write(h.array(), 0, 8);
                out.write(nd, 0, nd.length);
            }
            pos = off + c.getValue();
        }
        out.write(file, pos, file.length - pos);
        byte[] b = out.toByteArray();
        ByteBuffer bb = ByteBuffer.wrap(b).order(o);

        // RIFX length, imap's pointer to the map, and the map's entries
        bb.putInt(4, b.length - 8);
        int newMmap = mmapOffset + shiftFor(shift, mmapOffset);
        bb.putInt(12 + 8 + 4, newMmap);
        int nbase = newMmap + 8;
        for (int i = 0; i < used; i++) {
            int e = nbase + headerSize + i * entrySize;
            String cc = DirectorExtract.fourCC(bb.getInt(e));
            int off = bb.getInt(e + 8);
            if (cc.equals("free") || cc.equals("junk")) continue;
            if (replaced.containsKey(i)) bb.putInt(e + 4, replaced.get(i).length);
            if (off > 0 && off < file.length && shift.containsKey(off)) bb.putInt(e + 8, off + shift.get(off));
        }
        return b;
    }

    static int shiftFor(TreeMap<Integer, Integer> shift, int off) {
        Integer s = shift.get(off);
        return s == null ? 0 : s;
    }
}
