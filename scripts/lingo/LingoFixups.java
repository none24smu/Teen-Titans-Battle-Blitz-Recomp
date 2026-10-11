// Corrects known ProjectorRays 0.2.0 mistakes in decompiled Lingo, using the bytecode.
//
//   java LingoFixups <movie.dir|cast.cst> <decompiled folder>
//
// put ... into/after/before <local>: Director stores the local's plain index, but ProjectorRays
// divides it by 8 (as it does for getlocal), so it prints the wrong variable, e.g.
// "put RETURN after classNames" for what is really "put RETURN after out1". Only lines that end
// in the misprinted name are changed, so running this twice is harmless.

import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class LingoFixups {

    static final Charset CS = Charset.forName("windows-1252");
    static final Pattern PUT_LINE = Pattern.compile("(^|\\bthen\\s+|\\belse\\s+|^\\s*)put\\s.*\\s(into|after|before)\\s.*", Pattern.CASE_INSENSITIVE);
    static final Pattern HANDLER = Pattern.compile("^on\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    public static void main(String[] a) throws Exception {
        DirectorExtract d = new DirectorExtract(Files.readAllBytes(Paths.get(a[0])));
        d.readCasts();
        Path dir = Paths.get(a[1]);
        Map<Integer, Integer> scriptIds = LingoRoundTrip.scriptIds(d);
        int fixed = 0;
        for (DirectorExtract.Chunk c : d.chunks.values()) {
            // only Director 8 (Lctx) scripts: from 8.5 (LctX) on, ProjectorRays reads variable numbers right
            if (!c.fourCC.equals("Lctx")) continue;
            LingoScript.Context ctx = LingoScript.readContext(d.data(c));
            if (!d.chunks.containsKey(ctx.lnamSection)) continue;
            List<String> names = LingoScript.readNames(d.data(d.chunks.get(ctx.lnamSection)));
            for (int entry = 1; entry <= ctx.scriptSections.size(); entry++) {
                int sec = ctx.scriptSections.get(entry - 1);
                if (sec <= 0 || !d.chunks.containsKey(sec)) continue;
                LingoScript.Script s = LingoScript.readScript(d.data(d.chunks.get(sec)));
                Integer live = scriptIds.get(s.castID);
                if (live != null && live != entry) continue;
                Path src = LingoRoundTrip.source(d, dir, s.castID);
                if (src == null) continue;
                List<String> lines = new ArrayList<>(Arrays.asList(new String(Files.readAllBytes(src), CS).split("\r\n|\r|\n", -1)));
                int n = fixPuts(s, names, lines);
                if (n > 0) {
                    Files.write(src, String.join("\n", lines).getBytes(CS));
                    System.out.println("  fixed " + n + " put target(s) in " + src.getFileName());
                    fixed += n;
                }
            }
        }
        if (fixed == 0) System.out.println("  nothing to fix");
    }

    static int fixPuts(LingoScript.Script s, List<String> names, List<String> lines) {
        int fixed = 0;
        for (LingoScript.Handler h : s.handlers) {
            String hname = names.get(h.nameId);
            // the handler's lines
            int start = -1, end = lines.size();
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = HANDLER.matcher(lines.get(i));
                if (start < 0) { if (m.find() && m.group(1).equalsIgnoreCase(hname)) start = i; }
                else if (m.find()) { end = i; break; }
            }
            if (start < 0) continue;
            List<Integer> putLines = new ArrayList<>();
            for (int i = start; i < end; i++) if (PUT_LINE.matcher(stripComment(lines.get(i))).find()) putLines.add(i);
            // put/putchunk instructions in order, with the variable id pushed just before
            List<int[]> code = LingoRoundTrip.decode(h.bytecode);
            List<int[]> puts = new ArrayList<>(); // {varType, id}
            for (int k = 1; k < code.size(); k++) {
                int op = code.get(k)[0];
                if (op < 0x40 || (op % 0x40 != 0x19 && op % 0x40 != 0x1a)) continue;
                puts.add(new int[] {code.get(k)[1] & 0xf, code.get(k - 1)[1]});
            }
            if (puts.size() != putLines.size()) {
                if (!puts.isEmpty()) System.err.println("  warning: on " + hname + ": " + puts.size() + " put instructions but " + putLines.size() + " put lines; left as is");
                continue;
            }
            for (int k = 0; k < puts.size(); k++) {
                int type = puts.get(k)[0], id = puts.get(k)[1];
                if (type != 5 || id < 0 || id >= h.localNames.size()) continue;
                String right = names.get(h.localNames.get(id));
                String wrong = id / 8 < h.localNames.size() ? names.get(h.localNames.get(id / 8)) : null;
                if (wrong == null || wrong.equals(right)) continue;
                int li = putLines.get(k);
                String line = lines.get(li);
                Matcher m = Pattern.compile("\\b" + Pattern.quote(wrong) + "(\\s*)$").matcher(line);
                if (m.find()) { lines.set(li, line.substring(0, m.start()) + right + m.group(1)); fixed++; }
            }
        }
        return fixed;
    }

    static String stripComment(String l) {
        boolean str = false;
        for (int i = 0; i < l.length(); i++) {
            if (l.charAt(i) == '"') str = !str;
            else if (!str && l.startsWith("--", i)) return l.substring(0, i);
        }
        return l;
    }
}
