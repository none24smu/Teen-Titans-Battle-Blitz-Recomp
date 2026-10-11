// Round-trip test for the Lingo compiler: compiles every decompiled .ls script of a movie and
// compares the result with the bytecode in the movie, handler by handler.
//
//   java LingoRoundTrip <movie.dir> <decompiled movie folder> [-v [N]]
//
// The decompiled folder is the one ProjectorRays writes next to the movie
// (<folder>/casts/<cast name>/<Type> <member> - <name>.ls).

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class LingoRoundTrip {

    // opcodes (low 6 bits of multi-byte ops) whose operand is a name id
    static final Set<Integer> NAME_OPS = new HashSet<>(Arrays.asList(
        0x05, 0x06, 0x08, 0x09, 0x0a, 0x0e, 0x0f, 0x10, 0x17, 0x18, 0x1f, 0x20, 0x21, 0x22, 0x23, 0x26, 0x27,
        0x30, 0x32, 0x33));

    static final class Result { int scripts, scriptsOk, handlers, handlersOk, missing, failed, orphans; }

    public static void main(String[] a) throws Exception {
        DirectorExtract d = new DirectorExtract(Files.readAllBytes(Paths.get(a[0])));
        d.readCasts();
        Path dir = Paths.get(a[1]);
        boolean verbose = a.length > 2 && a[2].equals("-v");
        int show = a.length > 3 ? Integer.parseInt(a[3]) : 5;

        Map<Integer, Integer> scriptIds = scriptIds(d);
        Result r = new Result();
        Map<String, Integer> firstDiffs = new HashMap<>();
        int shown = 0;
        for (DirectorExtract.Chunk c : d.chunks.values()) {
            if (!c.fourCC.equals("Lctx") && !c.fourCC.equals("LctX")) continue;
            LingoScript.Context ctx = LingoScript.readContext(d.data(c));
            if (!d.chunks.containsKey(ctx.lnamSection)) continue;
            List<String> nameList = LingoScript.readNames(d.data(d.chunks.get(ctx.lnamSection)));
            for (int entry = 1; entry <= ctx.scriptSections.size(); entry++) {
                int sec = ctx.scriptSections.get(entry - 1);
                if (sec <= 0 || !d.chunks.containsKey(sec)) continue;
                LingoScript.Script orig = LingoScript.readScript(d.data(d.chunks.get(sec)), c.fourCC.equals("LctX"));
                // a context can keep stale copies of a script; the cast member names the live one
                Integer live = scriptIds.get(orig.castID);
                if (live != null && live != entry) { r.orphans++; continue; }
                Path src = source(d, dir, orig.castID);
                if (src == null) { r.missing++; continue; }
                r.scripts++;
                String text = new String(Files.readAllBytes(src), Charset.forName("windows-1252"));
                LingoCompiler.CompiledScript comp;
                LingoCompiler.Names names = new LingoCompiler.Names(nameList);
                names.preferred = usedNames(orig);
                names.newFrom = newFrom(orig, nameList.size());
                try {
                    comp = LingoCompiler.compile(LingoCompiler.parse(text), names, c.fourCC.equals("Lctx"));
                } catch (RuntimeException e) {
                    r.failed++;
                    if (verbose) System.out.println(src.getFileName() + ": " + e.getMessage());
                    continue;
                }
                List<String> problems = new ArrayList<>();
                if (!orig.propertyNames.equals(comp.propertyNames)) problems.add("properties " + orig.propertyNames + " vs " + comp.propertyNames);
                if (!orig.globalNames.equals(comp.globalNames)) problems.add("globals " + orig.globalNames + " vs " + comp.globalNames);
                if (orig.handlers.size() != comp.handlers.size()) problems.add("handler count " + orig.handlers.size() + " vs " + comp.handlers.size());
                int n = Math.min(orig.handlers.size(), comp.handlers.size());
                for (int i = 0; i < n; i++) {
                    LingoScript.Handler oh = orig.handlers.get(i);
                    LingoCompiler.CompiledHandler ch = comp.handlers.get(i);
                    r.handlers++;
                    List<String> hp = new ArrayList<>();
                    if (oh.nameId != ch.nameId) hp.add("name " + oh.nameId + " vs " + ch.nameId);
                    if (!oh.argNames.equals(ch.argNames)) hp.add("args " + names(nameList, oh.argNames) + " vs " + names(names.list, ch.argNames));
                    if (!oh.localNames.equals(ch.localNames)) hp.add("locals " + names(nameList, oh.localNames) + " vs " + names(names.list, ch.localNames));
                    if (!oh.globalNames.equals(ch.globalNames)) hp.add("globals " + names(nameList, oh.globalNames) + " vs " + names(names.list, ch.globalNames));
                    if (!Arrays.equals(oh.bytecode, ch.bytecode)) {
                        String diff = firstDiff(oh.bytecode, ch.bytecode, names.list);
                        hp.add("bytecode " + diff);
                        firstDiffs.merge(diffKey(oh.bytecode, ch.bytecode), 1, Integer::sum);
                    }
                    if (hp.isEmpty()) r.handlersOk++;
                    else problems.add("on " + nameList.get(oh.nameId) + ": " + String.join("; ", hp));
                }
                if (!orig.literals.equals(comp.literals)) problems.add("literals " + orig.literals + " vs " + comp.literals);
                if (problems.isEmpty()) r.scriptsOk++;
                else if (verbose && shown++ < show) {
                    System.out.println("== " + src.getFileName());
                    for (String p : problems) System.out.println("   " + p);
                }
            }
        }
        System.out.printf("scripts: %d/%d identical (%d failed to compile, %d without source, %d stale copies skipped)%n",
            r.scriptsOk, r.scripts, r.failed, r.missing, r.orphans);
        System.out.printf("handlers: %d/%d identical%n", r.handlersOk, r.handlers);
        if (!firstDiffs.isEmpty()) {
            System.out.println("most common first differences (original | compiled):");
            firstDiffs.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(20)
                .forEach(e -> System.out.println("  " + e.getValue() + "x  " + e.getKey()));
        }
        if (r.scriptsOk != r.scripts || r.failed > 0) System.exit(1);
    }

    static String names(List<String> all, List<Integer> ids) {
        return ids.stream().map(i -> i >= 0 && i < all.size() ? all.get(i) : "#" + i).collect(Collectors.joining(",", "[", "]"));
    }

    /** The .ls file ProjectorRays wrote for the script member castLib<<16 | member. */
    static Path source(DirectorExtract d, Path dir, int castID) throws IOException {
        int lib = castID >>> 16, member = castID & 0xffff;
        if (lib < 1 || lib > d.castLibs.size()) return null;
        Path castDir = dir.resolve("casts").resolve(d.castLibs.get(lib - 1).name);
        if (!Files.isDirectory(castDir)) return null;
        try (Stream<Path> s = Files.list(castDir)) {
            return s.filter(p -> {
                String f = p.getFileName().toString();
                return f.endsWith(".ls") && f.matches("\\S+ " + member + "( - .*)?\\.ls");
            }).findFirst().orElse(null);
        }
    }

    /** castLib<<16 | member -> the script context entry (1-based) that script member uses. */
    static Map<Integer, Integer> scriptIds(DirectorExtract d) {
        Map<Integer, Integer> m = new HashMap<>();
        for (DirectorExtract.Member mem : d.members)
            if (mem.type == 11 && mem.info != null && mem.info.length >= 20)
                m.put(((mem.castLibIndex + 1) << 16) | mem.number, java.nio.ByteBuffer.wrap(mem.info).getInt(16));
        return m;
    }

    static Set<Integer> usedNames(LingoScript.Script s) {
        Set<Integer> ids = new HashSet<>(s.propertyNames);
        ids.addAll(s.globalNames);
        for (LingoScript.Handler h : s.handlers) {
            ids.add(h.nameId); ids.addAll(h.argNames); ids.addAll(h.localNames); ids.addAll(h.globalNames);
            for (int[] ins : decode(h.bytecode)) if (ins[0] >= 0x40 && NAME_OPS.contains(ins[0] % 0x40)) ids.add(ins[1]);
        }
        return ids;
    }

    /** The first name id the original compile treated as new: the smallest one written wide although it fits a byte. */
    static int newFrom(LingoScript.Script s, int count) {
        int from = count;
        for (LingoScript.Handler h : s.handlers)
            for (int[] ins : decode(h.bytecode))
                if (ins[0] >= 0x80 && ins[0] < 0xc0 && NAME_OPS.contains(ins[0] % 0x40) && ins[1] < 256) from = Math.min(from, ins[1]);
        return from;
    }

    /** Instructions as {opcode, operand, position}. */
    static List<int[]> decode(byte[] code) {
        List<int[]> out = new ArrayList<>();
        int p = 0;
        while (p < code.length) {
            int op = code[p] & 0xff, start = p++, arg = 0;
            if (op >= 0xc0) { arg = ((code[p] & 0xff) << 24) | ((code[p + 1] & 0xff) << 16) | ((code[p + 2] & 0xff) << 8) | (code[p + 3] & 0xff); p += 4; }
            else if (op >= 0x80) { arg = ((code[p] & 0xff) << 8) | (code[p + 1] & 0xff); if (op % 0x40 == 0x01 || op % 0x40 == 0x2e) arg = (short) arg; p += 2; }
            else if (op >= 0x40) { arg = op % 0x40 == 0x01 ? code[p] : code[p] & 0xff; p += 1; }
            out.add(new int[] {op, arg, start});
        }
        return out;
    }

    static String insn(int[] i, List<String> names) {
        String n = LingoScript.opName(i[0]);
        if (i[0] < 0x40) return n;
        String arg = Integer.toString(i[1]);
        if (NAME_OPS.contains(i[0] % 0x40) && i[1] >= 0 && i[1] < names.size()) arg += "(" + names.get(i[1]) + ")";
        return n + " " + arg;
    }

    static int firstDiffIndex(List<int[]> x, List<int[]> y) {
        int i = 0;
        while (i < x.size() && i < y.size() && x.get(i)[0] == y.get(i)[0] && x.get(i)[1] == y.get(i)[1]) i++;
        return i;
    }

    static String firstDiff(byte[] a, byte[] b, List<String> names) {
        List<int[]> x = decode(a), y = decode(b);
        int i = firstDiffIndex(x, y);
        StringBuilder s = new StringBuilder("at insn " + i + ":");
        for (int k = Math.max(0, i - 2); k < i + 4; k++) {
            s.append("\n        ").append(k < x.size() ? String.format("%-36s", insn(x.get(k), names)) : String.format("%-36s", "-"));
            s.append(k < y.size() ? insn(y.get(k), names) : "-");
            if (k == i) s.append("   <==");
        }
        return s.toString();
    }

    static String diffKey(byte[] a, byte[] b) {
        List<int[]> x = decode(a), y = decode(b);
        int i = firstDiffIndex(x, y);
        String l = i < x.size() ? LingoScript.opName(x.get(i)[0]) : "end";
        String r = i < y.size() ? LingoScript.opName(y.get(i)[0]) : "end";
        return l + " | " + r;
    }
}
