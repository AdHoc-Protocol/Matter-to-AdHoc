package org.unirail;

import org.unirail.adhoc.AdHocWriter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.unirail.adhoc.AdHocWriter.I1;
import static org.unirail.adhoc.AdHocWriter.I2;
import static org.unirail.adhoc.AdHocWriter.I3;
import static org.unirail.adhoc.AdHocWriter.I4;
import static org.unirail.adhoc.AdHocWriter.brush;
import static org.unirail.adhoc.AdHocWriter.doc;
import static org.unirail.adhoc.AdHocWriter.ident;
import static org.unirail.adhoc.AdHocWriter.str;

/**
 * Matter (CSA) data-model cluster XML → AdHoc protocol description (.cs).
 *
 * <p>Input : a cluster XML from {@code connectedhomeip/data_model/<version>/clusters/}, or a folder of them. Global
 * types ({@code data_model/<version>/globals/*.xml}) are picked up from a {@code globals} sub-folder of the input
 * folder (or {@code --globals <dir>}); only the global types a cluster actually references are emitted into it.
 * <p>Output: one {@code <Cluster>.cs} per cluster file, hosts {@code Client} (controller) and {@code Server} (device).
 *
 * <p>Usage: {@code java -cp out org.unirail.Matter2AdHoc <cluster.xml | folder> [output folder] [--globals <dir>]}
 */
public class Matter2AdHoc {

	public static void main(String[] args) throws Exception {
		List<String> positional = new ArrayList<>();
		String globalsDir = null;
		for (int i = 0; i < args.length; i++)
			if (args[i].equals("--globals") && i + 1 < args.length) globalsDir = args[++i];
			else positional.add(args[i]);
		if (positional.isEmpty()) {
			System.out.println("Usage: java -cp out org.unirail.Matter2AdHoc <cluster.xml | folder> [output folder] [--globals <dir>]");
			System.out.println("       output defaults to <current dir>/AdHoc; globals default to <input folder>/globals");
			return;
		}
		Path src = Paths.get(positional.get(0));
		Path dst = 1 < positional.size() ? Paths.get(positional.get(1)) : Paths.get(System.getProperty("user.dir"), "AdHoc");
		Path inputDir = Files.isDirectory(src) ? src : src.toAbsolutePath().getParent();
		Path globals = globalsDir != null ? Paths.get(globalsDir) : inputDir.resolve("globals");

		File[] files = Files.isDirectory(src) ? src.toFile().listFiles((d, n) -> n.endsWith(".xml")) : new File[]{src.toFile()};
		if (files == null || files.length == 0) {
			System.err.println("No .xml files found in `" + src.toAbsolutePath() + "`.");
			System.exit(1);
			return;
		}
		Arrays.sort(files);
		Files.createDirectories(dst);

		Pool pool = new Pool();
		if (Files.isDirectory(globals)) {
			File[] g = globals.toFile().listFiles((d, n) -> n.endsWith(".xml"));
			if (g != null) for (File f : g) pool.loadGlobals(f.toPath());
			System.out.println("globals: " + pool.enums.size() + " enums, " + pool.bitmaps.size() + " bitmaps, " + pool.structs.size() + " structs, " + pool.typedefs.size() + " typedefs from " + globals);
		}

		int failed = 0;
		for (File file : files) {
			try {
				Cluster c = Cluster.load(file.toPath());
				if (c == null) { System.out.println(file.getName() + ": not a <cluster> document, skipped"); continue; }
				Path out = dst.resolve(c.project + ".cs");
				Files.write(out, new Emitter(c, pool).emit().getBytes(StandardCharsets.UTF_8));
				System.out.printf("%-32s -> %s  (%d attributes, %d commands, %d events)%n", file.getName(), out, c.attrs.size(), c.commands.size(), c.events.size());
			} catch (Exception e) {
				failed++;
				System.err.println("FAILED " + file + ": " + e);
				e.printStackTrace();
			}
		}
		if (0 < failed) System.exit(2);
	}

	// ═══════════════════════════════════════════ model ═══════════════════════════════════════════

	static class Field {
		String id, name, type, entryType, dflt, summary = "";
		boolean nullable, optional;
		String constraint = "", entryConstraint = "", conformance = "";
		long[] range;          // hard bounds {min, max} of a <between> whose ends are both literals
		Long cMin, cMax;       // literal <min> / <max> bounds
		boolean symbolicBound; // a bound exists but references another attribute or a computed expression
		int maxLength = -1, maxCount = -1, entryMaxLength = -1;
	}

	static final class Attr extends Field {
		String access = "", quality = "";
	}

	static final class Struct {
		String name, origin;
		final List<Field> fields = new ArrayList<>();
	}

	static final class EnumT {
		String name, origin;
		final List<String[]> items = new ArrayList<>(); // value, name, summary
	}

	static final class Bitmap {
		String name, origin;
		final List<Object[]> bits = new ArrayList<>(); // name, low, high, summary
	}

	static final class Command {
		String id, name, direction, response, access = "", conformance = "";
		final List<Field> fields = new ArrayList<>();
	}

	static final class Event {
		String id, name, priority, access = "", conformance = "";
		final List<Field> fields = new ArrayList<>();
	}

	static final class Feature {
		int bit;
		String code, name, summary;
	}

	/** Global types shared by all clusters (data_model/<ver>/globals). */
	static class Pool {
		final Map<String, EnumT> enums = new LinkedHashMap<>();
		final Map<String, Bitmap> bitmaps = new LinkedHashMap<>();
		final Map<String, Struct> structs = new LinkedHashMap<>();
		final Map<String, String> typedefs = new LinkedHashMap<>();

		void loadGlobals(Path xml) throws Exception {
			Element root = parse(xml).getDocumentElement();
			String origin = xml.getFileName().toString();
			readTypes(root, origin, this);
		}
	}

	static final class Cluster extends Pool {
		String file, project, id, displayName, revision;
		final List<String> history = new ArrayList<>();
		final List<Feature> features = new ArrayList<>();
		final List<Attr> attrs = new ArrayList<>();
		final List<Command> commands = new ArrayList<>();
		final List<Event> events = new ArrayList<>();

		static Cluster load(Path xml) throws Exception {
			Element root = parse(xml).getDocumentElement();
			if (!root.getTagName().equals("cluster")) return null;
			Cluster c = new Cluster();
			c.file = xml.getFileName().toString();
			String base = c.file.substring(0, c.file.length() - ".xml".length());
			if (base.endsWith("-Cluster")) base = base.substring(0, base.length() - "-Cluster".length());
			if (base.endsWith("Cluster") && base.length() > 7) base = base.substring(0, base.length() - "Cluster".length());
			c.project = ident(base);
			c.id = attr(root, "id");
			c.displayName = attr(root, "name");
			c.revision = attr(root, "revision");
			for (Element h : children(root, "revisionHistory"))
				for (Element r : children(h, "revision")) {
					String s = attr(r, "summary");
					if (s != null) c.history.add("rev " + attr(r, "revision") + ": " + s);
				}
			for (Element ids : children(root, "clusterIds"))
				for (Element ci : children(ids, "clusterId"))
					if (c.id == null) c.id = attr(ci, "id");
			for (Element fs : children(root, "features"))
				for (Element f : children(fs, "feature")) {
					Feature x = new Feature();
					x.bit = Integer.decode(attr(f, "bit"));
					x.code = attr(f, "code");
					x.name = attr(f, "name");
					x.summary = attr(f, "summary");
					c.features.add(x);
				}
			for (Element dt : children(root, "dataTypes")) readTypes(dt, c.file, c);
			for (Element as : children(root, "attributes"))
				for (Element a : children(as, "attribute")) {
					Attr x = new Attr();
					readField(a, x);
					x.access = access(a);
					x.quality = quality(a);
					c.attrs.add(x);
				}
			for (Element cs : children(root, "commands"))
				for (Element e : children(cs, "command")) {
					Command x = new Command();
					x.id = attr(e, "id");
					x.name = attr(e, "name");
					x.direction = attr(e, "direction");
					x.response = attr(e, "response");
					x.access = access(e);
					x.conformance = conformance(e);
					for (Element f : children(e, "field")) { Field fl = new Field(); readField(f, fl); x.fields.add(fl); }
					c.commands.add(x);
				}
			for (Element es : children(root, "events"))
				for (Element e : children(es, "event")) {
					Event x = new Event();
					x.id = attr(e, "id");
					x.name = attr(e, "name");
					x.priority = attr(e, "priority");
					x.access = access(e);
					x.conformance = conformance(e);
					for (Element f : children(e, "field")) { Field fl = new Field(); readField(f, fl); x.fields.add(fl); }
					c.events.add(x);
				}
			return c;
		}
	}

	/** Reads enum / bitmap / struct / typedef declarations found anywhere under {@code root} (cluster dataTypes or a globals file). */
	static void readTypes(Element root, String origin, Pool into) {
		for (Element e : descendants(root, "enum")) {
			if (e.getParentNode() instanceof Element && ((Element) e.getParentNode()).getTagName().equals("allowed")) continue; // constraint value, not a type
			if (attr(e, "name") == null) continue;
			EnumT en = new EnumT();
			en.name = attr(e, "name");
			en.origin = origin;
			for (Element it : children(e, "item")) en.items.add(new String[]{attr(it, "value"), attr(it, "name"), attr(it, "summary")});
			into.enums.putIfAbsent(en.name, en);
		}
		for (Element b : descendants(root, "bitmap")) {
			if (attr(b, "name") == null) continue;
			Bitmap bm = new Bitmap();
			bm.name = attr(b, "name");
			bm.origin = origin;
			for (Element bf : children(b, "bitfield")) {
				String bit = attr(bf, "bit");
				int lo = Integer.decode(bit != null ? bit : attr(bf, "from"));
				int hi = bit != null ? lo : Integer.decode(attr(bf, "to"));
				bm.bits.add(new Object[]{attr(bf, "name"), lo, hi, attr(bf, "summary")});
			}
			into.bitmaps.putIfAbsent(bm.name, bm);
		}
		for (Element s : descendants(root, "struct")) {
			if (attr(s, "name") == null) continue;
			Struct st = new Struct();
			st.name = attr(s, "name");
			st.origin = origin;
			for (Element f : children(s, "field")) { Field fl = new Field(); readField(f, fl); st.fields.add(fl); }
			into.structs.putIfAbsent(st.name, st);
		}
		for (Element td : descendants(root, "typeDefs"))
			for (Element n : children(td))
				if (attr(n, "name") != null && attr(n, "type") != null) into.typedefs.putIfAbsent(attr(n, "name"), attr(n, "type"));
	}

	static void readField(Element e, Field f) {
		f.id = attr(e, "id");
		f.name = attr(e, "name");
		f.type = attr(e, "type");
		f.dflt = attr(e, "default");
		String s = attr(e, "summary");
		if (s != null) f.summary = s;
		for (Element d : children(e, "default")) f.dflt = value(d);
		for (Element q : children(e, "quality")) if ("true".equals(attr(q, "nullable"))) f.nullable = true;
		if ("null".equals(f.dflt)) f.nullable = true;
		f.conformance = conformance(e);
		f.optional = !f.conformance.equals("M");
		for (Element en : children(e, "entry")) {
			f.entryType = attr(en, "type");
			for (Element c : children(en, "constraint")) {
				f.entryConstraint = constraint(c);
				f.entryMaxLength = maxLength(c);
			}
		}
		for (Element c : children(e, "constraint")) {
			f.constraint = constraint(c);
			f.range = literalRange(c);
			for (Element x : children(c, "min")) f.cMin = literal(x);
			for (Element x : children(c, "max")) f.cMax = literal(x);
			f.symbolicBound = symbolicBound(c);
			f.maxLength = maxLength(c);
			f.maxCount = maxCount(c);
		}
	}

	// ───────────────────────────── access / quality / conformance / constraint rendering ─────────────────────────────

	static String access(Element e) {
		for (Element a : children(e, "access")) {
			List<String> parts = new ArrayList<>();
			if ("true".equals(attr(a, "read"))) parts.add("read:" + nz(attr(a, "readPrivilege")));
			else if (attr(a, "readPrivilege") != null) parts.add("read:" + attr(a, "readPrivilege"));
			if (attr(a, "write") != null) parts.add(("optional".equals(attr(a, "write")) ? "write?:" : "write:") + nz(attr(a, "writePrivilege")));
			if (attr(a, "invokePrivilege") != null) parts.add("invoke:" + attr(a, "invokePrivilege"));
			if ("true".equals(attr(a, "timed"))) parts.add("timed");
			if ("true".equals(attr(a, "fabricScoped"))) parts.add("fabricScoped");
			if ("true".equals(attr(a, "fabricSensitive"))) parts.add("fabricSensitive");
			return String.join(" ", parts);
		}
		return "";
	}

	static String quality(Element e) {
		for (Element q : children(e, "quality")) {
			List<String> parts = new ArrayList<>();
			if ("true".equals(attr(q, "nullable"))) parts.add("nullable");
			if (attr(q, "persistence") != null) parts.add(attr(q, "persistence"));
			if ("true".equals(attr(q, "scene"))) parts.add("scene");
			if ("true".equals(attr(q, "quieterReporting"))) parts.add("quieterReporting");
			if ("true".equals(attr(q, "atomicWrite"))) parts.add("atomicWrite");
			if ("true".equals(attr(q, "reportable"))) parts.add("reportable");
			if ("true".equals(attr(q, "changeOmitted"))) parts.add("changeOmitted");
			if ("true".equals(attr(q, "singleton"))) parts.add("singleton");
			if ("true".equals(attr(q, "largeMessage"))) parts.add("largeMessage");
			return String.join(" ", parts);
		}
		return "";
	}

	/** "M", "O", "M[LT]", "O[!OFFONLY]", "P", "X", "otherwise(M[LT]; O)" ... */
	static String conformance(Element parent) {
		List<String> out = new ArrayList<>();
		for (Element c : children(parent)) {
			String r = conformanceOne(c);
			if (r != null) out.add(r);
		}
		return String.join("; ", out);
	}

	static String conformanceOne(Element c) {
		switch (c.getTagName()) {
			case "mandatoryConform": return "M" + cond(c);
			case "optionalConform": return "O" + cond(c) + (attr(c, "choice") != null ? "{choice " + attr(c, "choice") + ("true".equals(attr(c, "more")) ? "+" : "") + "}" : "");
			case "provisionalConform": return "P" + cond(c);
			case "obsoleteConform": return "X" + cond(c);
			case "deprecateConform": return "D" + cond(c);
			case "disallowConform": return "X" + cond(c);
			case "describedConform": return "desc";
			case "otherwiseConform": {
				List<String> parts = new ArrayList<>();
				for (Element x : children(c)) { String r = conformanceOne(x); if (r != null) parts.add(r); }
				return "otherwise(" + String.join("; ", parts) + ")";
			}
			default: return null;
		}
	}

	static String cond(Element c) {
		List<Element> ch = children(c);
		return ch.isEmpty() ? "" : "[" + expr(ch) + "]";
	}

	static String expr(List<Element> list) {
		List<String> parts = new ArrayList<>();
		for (Element e : list) parts.add(expr(e));
		return String.join(",", parts);
	}

	static String expr(Element e) {
		switch (e.getTagName()) {
			case "feature":
			case "condition": return nz(attr(e, "name"));
			case "attribute": return "attr:" + nz(attr(e, "name"));
			case "command": return "cmd:" + nz(attr(e, "name"));
			case "literal": return nz(attr(e, "value"));
			case "notTerm": return "!" + expr(children(e));
			case "andTerm": return "(" + join(children(e), "&") + ")";
			case "orTerm": return "(" + join(children(e), "|") + ")";
			case "greaterTerm": return "(" + join(children(e), ">") + ")";
			case "greaterOrEqualTerm": return "(" + join(children(e), ">=") + ")";
			case "lessTerm": return "(" + join(children(e), "<") + ")";
			case "lessOrEqualTerm": return "(" + join(children(e), "<=") + ")";
			case "equalTerm": return "(" + join(children(e), "==") + ")";
			case "notEqualTerm": return "(" + join(children(e), "!=") + ")";
			default: return e.getTagName() + (attr(e, "name") != null ? ":" + attr(e, "name") : "");
		}
	}

	static String join(List<Element> list, String op) {
		List<String> parts = new ArrayList<>();
		for (Element e : list) parts.add(expr(e));
		return String.join(op, parts);
	}

	static String constraint(Element c) {
		List<String> parts = new ArrayList<>();
		for (Element x : children(c)) {
			switch (x.getTagName()) {
				case "desc": parts.add("desc"); break;
				case "between":
				case "lengthBetween":
				case "countBetween": {
					String from = "", to = "";
					for (Element f : children(x, "from")) from = value(f);
					for (Element t : children(x, "to")) to = value(t);
					parts.add(x.getTagName() + " " + from + ".." + to);
					break;
				}
				default: parts.add(x.getTagName() + " " + value(x));
			}
		}
		return String.join("; ", parts);
	}

	/** The value of a constraint node: its {@code value} attribute, an attribute reference, a computed expression or an enum value. */
	static String value(Element x) {
		if (attr(x, "value") != null) return attr(x, "value");
		for (Element ch : children(x))
			switch (ch.getTagName()) {
				case "attribute": return "attr:" + nz(attr(ch, "name"));
				case "enum": return nz(attr(ch, "value"));
				case "compute": {
					String op = "?";
					for (Element o : children(ch, "operation")) op = o.getTextContent().trim();
					String l = "", r = "";
					for (Element o : children(ch, "left")) l = value(o);
					for (Element o : children(ch, "right")) r = value(o);
					return "(" + l + " " + op + " " + r + ")";
				}
				default: return value(ch);
			}
		String t = x.getTextContent().trim();
		return t;
	}

	static long[] literalRange(Element c) {
		for (Element x : children(c, "between")) {
			Long from = null, to = null;
			for (Element f : children(x, "from")) from = literal(f);
			for (Element t : children(x, "to")) to = literal(t);
			if (from != null && to != null && from <= to) return new long[]{from, to};
		}
		return null;
	}

	static Long literal(Element x) {
		String v = attr(x, "value");
		if (v == null) return null;
		try { return Long.decode(v.trim()); } catch (NumberFormatException e) { return null; }
	}

	/** True when a numeric bound is present but is not a literal (an attribute reference or a computed expression). */
	static boolean symbolicBound(Element c) {
		for (Element x : children(c))
			switch (x.getTagName()) {
				case "min":
				case "max":
					if (literal(x) == null) return true;
					break;
				case "between":
					for (Element b : children(x)) if (literal(b) == null) return true;
					break;
				default:
			}
		return false;
	}

	static int maxLength(Element c) {
		for (Element x : children(c, "maxLength")) { Long v = literal(x); if (v != null) return v.intValue(); }
		for (Element x : children(c, "lengthBetween")) for (Element t : children(x, "to")) { Long v = literal(t); if (v != null) return v.intValue(); }
		for (Element x : children(c, "allowed")) { Long v = literal(x); if (v != null) return v.intValue(); } // octstr: fixed length
		return -1;
	}

	static int maxCount(Element c) {
		for (Element x : children(c, "maxCount")) { Long v = literal(x); if (v != null) return v.intValue(); }
		for (Element x : children(c, "countBetween")) for (Element t : children(x, "to")) { Long v = literal(t); if (v != null) return v.intValue(); }
		return -1;
	}

	// ═══════════════════════════════════════════ DOM helpers ═══════════════════════════════════════════

	static Document parse(Path xml) throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setNamespaceAware(false);
		return f.newDocumentBuilder().parse(xml.toFile());
	}

	static List<Element> children(Element e) {
		List<Element> list = new ArrayList<>();
		NodeList nl = e.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) if (nl.item(i) instanceof Element) list.add((Element) nl.item(i));
		return list;
	}

	static List<Element> children(Element e, String tag) {
		List<Element> list = new ArrayList<>();
		for (Element c : children(e)) if (c.getTagName().equals(tag)) list.add(c);
		return list;
	}

	static List<Element> descendants(Element e, String tag) {
		List<Element> list = new ArrayList<>();
		NodeList nl = e.getElementsByTagName(tag);
		for (int i = 0; i < nl.getLength(); i++) list.add((Element) nl.item(i));
		return list;
	}

	static String attr(Element e, String name) { return e.hasAttribute(name) ? e.getAttribute(name) : null; }

	static String nz(String s) { return s == null ? "" : s; }

	// ═══════════════════════════════════════════ type mapping ═══════════════════════════════════════════

	/** Matter base and semantic types → C# type (+ whether it is a value type that can take `?`). */
	static final Map<String, String> BASE = new HashMap<>();

	static {
		String[][] t = {
				{"bool", "bool"}, {"uint8", "byte"}, {"uint16", "ushort"}, {"uint24", "uint"}, {"uint32", "uint"}, {"uint40", "ulong"},
				{"uint48", "ulong"}, {"uint56", "ulong"}, {"uint64", "ulong"}, {"int8", "sbyte"}, {"int16", "short"}, {"int24", "int"},
				{"int32", "int"}, {"int40", "long"}, {"int48", "long"}, {"int56", "long"}, {"int64", "long"}, {"single", "float"},
				{"double", "double"}, {"enum8", "byte"}, {"enum16", "ushort"}, {"map8", "byte"}, {"map16", "ushort"}, {"map32", "uint"},
				{"map64", "ulong"}, {"int16s", "short"},
				// semantic types (Matter core spec 7.19)
				{"percent", "byte"}, {"percent100ths", "ushort"}, {"temperature", "short"}, {"epoch-s", "uint"}, {"epoch-us", "ulong"},
				{"elapsed-s", "uint"}, {"systime-ms", "ulong"}, {"systime-us", "ulong"}, {"posix-ms", "ulong"}, {"utc", "uint"},
				{"date", "uint"}, {"tod", "uint"}, {"status", "byte"}, {"priority", "byte"}, {"fabric-id", "ulong"}, {"fabric-idx", "byte"},
				{"node-id", "ulong"}, {"endpoint-no", "ushort"}, {"cluster-id", "uint"}, {"attrib-id", "uint"}, {"attribute-id", "uint"},
				{"field-id", "uint"}, {"event-id", "uint"}, {"command-id", "uint"}, {"devtype-id", "uint"}, {"vendor-id", "ushort"},
				{"group-id", "ushort"}, {"action-id", "byte"}, {"trans-id", "uint"}, {"entry-idx", "ushort"}, {"data-ver", "uint"},
				{"event-no", "ulong"}, {"subject-id", "ulong"}, {"namespace", "byte"}, {"tag", "byte"}, {"power-mW", "long"},
				{"power-mVA", "long"}, {"power-mVAR", "long"}, {"amperage-mA", "long"}, {"voltage-mV", "long"}, {"energy-mWh", "long"},
				{"energy-mVAh", "long"}, {"energy-mVARh", "long"}, {"money", "long"}, {"hwadr", "ulong"}, {"semtag", "uint"},
				// derived temperature types (Matter core spec): a temperature in 0.1 °C steps, narrowed to one byte
				{"SignedTemperature", "sbyte"}, {"UnsignedTemperature", "byte"}, {"TemperatureDifference", "short"},
		};
		for (String[] x : t) BASE.put(x[0], x[1]);
	}

	static final Set<String> PRIMITIVE_BASE = new HashSet<>(Arrays.asList("bool", "uint8", "uint16", "uint24", "uint32", "uint40", "uint48",
			"uint56", "uint64", "int8", "int16", "int24", "int32", "int40", "int48", "int56", "int64", "single", "double", "string", "octstr", "list"));

	static final Set<String> UNSIGNED = new HashSet<>(Arrays.asList("byte", "ushort", "uint", "ulong"));
	static final Set<String> INTEGER = new HashSet<>(Arrays.asList("byte", "ushort", "uint", "ulong", "sbyte", "short", "int", "long"));

	/**
	 * Matter wall-clock timestamps. AdHoc models a point in time natively, so these become {@code DateTime}
	 * instead of an integer carrying a {@code [MatterType]} tag. The Matter epoch (2000-01-01 for `epoch-*`,
	 * 1970-01-01 for `posix-ms`/`utc`) is a wire detail AdHoc replaces with its own encoding.
	 */
	static final Set<String> DATETIME = new HashSet<>(Arrays.asList("epoch-s", "epoch-us", "posix-ms", "utc"));

	/**
	 * Matter elapsed / monotonic times. Each becomes one shared {@code class X : Duration} alias named after its
	 * unit, so every field of that Matter type refers to the same declaration.
	 * Values: alias name, precision expression, max in steps of that precision, doc.
	 */
	static final Map<String, String[]> DURATIONS = new LinkedHashMap<>();

	static {
		DURATIONS.put("elapsed-s", new String[]{"ElapsedSeconds", "TimeSpan.FromSeconds(1)", "4_294_967_295",
				"Matter elapsed-s: a duration in seconds (uint32 on the Matter wire)."});
		DURATIONS.put("systime-ms", new String[]{"SystemTimeMilliseconds", "TimeSpan.FromMilliseconds(1)", "9_007_199_254_740_991",
				"Matter systime-ms: milliseconds since boot (uint64 on the Matter wire, capped here at the AdHoc/JS safe-integer limit)."});
		DURATIONS.put("systime-us", new String[]{"SystemTimeMicroseconds", "TimeSpan.FromMicroseconds(1)", "9_007_199_254_740_991",
				"Matter systime-us: microseconds since boot (uint64 on the Matter wire, capped here at the AdHoc/JS safe-integer limit)."});
	}

	/** Inclusive range of a C# integer type, or null when it does not fit a Java long (ulong) or is not an integer. */
	static long[] typeBounds(String cs) {
		switch (cs) {
			case "byte": return new long[]{0, 255};
			case "sbyte": return new long[]{-128, 127};
			case "ushort": return new long[]{0, 65_535};
			case "short": return new long[]{-32_768, 32_767};
			case "uint": return new long[]{0, 4_294_967_295L};
			case "int": return new long[]{Integer.MIN_VALUE, Integer.MAX_VALUE};
			case "long": return new long[]{Long.MIN_VALUE, Long.MAX_VALUE};
			default: return null; // ulong: the upper bound does not fit a Java long
		}
	}

	// ═══════════════════════════════════════════ emitter ═══════════════════════════════════════════

	static final class Emitter {
		final Cluster c;
		final Pool globals;
		final StringBuilder sb = new StringBuilder(1 << 16);

		/** Source type name → emitted AdHoc name (enums, bitmaps, structs, typedef targets). */
		final Map<String, String> typeNames = new LinkedHashMap<>();
		/** Types (by source name) whose emitted form is a constants container, not a usable field type. */
		final Set<String> containers = new HashSet<>();
		final Set<String> taken = new HashSet<>();      // names used at the project-interface level
		final Set<String> connTaken = new HashSet<>();  // names used inside the connection interface
		final Map<String, EnumT> enums = new LinkedHashMap<>();
		final Map<String, Bitmap> bitmaps = new LinkedHashMap<>();
		final Map<String, Struct> structs = new LinkedHashMap<>();
		final Map<String, String> octstrTypedefs = new LinkedHashMap<>(); // max length → typedef name
		final Map<String, String> usedDurations = new LinkedHashMap<>(); // Matter type → emitted Duration alias name
		final List<String> notes = new ArrayList<>();

		Emitter(Cluster c, Pool globals) {
			this.c = c;
			this.globals = globals;
		}

		String emit() {
			taken.add(c.project);
			taken.add("Client");
			taken.add("Server");
			taken.add("Interaction");
			collectTypes();

			// names of packs first, so field types and the Dashboard agree
			String infoName = reserve("ClusterInfo");
			String featuresName = reserve("Features");
			for (EnumT e : enums.values()) typeNames.put(e.name, reserve(e.name));
			for (Bitmap b : bitmaps.values()) typeNames.put(b.name, reserve(b.name));
			for (Struct s : structs.values()) typeNames.put(s.name, reserve(s.name));
			String attrsName = reserve("Attributes");
			Map<Command, String> reqName = new LinkedHashMap<>(), respName = new LinkedHashMap<>();
			Map<String, Command> responses = new HashMap<>();
			for (Command cmd : c.commands) if ("responseFromServer".equals(cmd.direction)) responses.put(cmd.name, cmd);
			Map<Command, Command> rpc = new LinkedHashMap<>();
			List<Command> fireAndForget = new ArrayList<>();
			for (Command cmd : c.commands) {
				if (!"commandToServer".equals(cmd.direction)) continue;
				Command resp = cmd.response == null || cmd.response.equals("Y") || cmd.response.equals("N") ? null : responses.get(cmd.response);
				if (cmd.response != null && !cmd.response.equals("Y") && !cmd.response.equals("N") && resp == null)
					notes.add("command " + cmd.name + ": response " + cmd.response + " is not declared in this file, treated as fire-and-forget");
				if (resp != null) {
					rpc.put(cmd, resp);
					reqName.put(cmd, reserve(cmd.name + "Request"));
					respName.put(resp, reserve(resp.name));
				} else {
					fireAndForget.add(cmd);
					reqName.put(cmd, reserve(cmd.name.equals(c.project) ? cmd.name + "Command" : cmd.name));
				}
			}
			for (Command cmd : c.commands)
				if ("responseFromServer".equals(cmd.direction) && !respName.containsKey(cmd)) {
					respName.put(cmd, reserve(cmd.name));
					notes.add("response " + cmd.name + " is not referenced by any command; emitted as a server-sent pack");
				}
			Map<Event, String> eventName = new LinkedHashMap<>();
			for (Event ev : c.events) eventName.put(ev, reserve(taken.contains(ev.name) ? ev.name + "Event" : ev.name));

			// typedefs for octet-string list items, and the Duration aliases the fields will refer to
			for (Field f : allFields()) {
				if ("list".equals(f.type) && "octstr".equals(f.entryType)) octstrTypedef(f.entryMaxLength);
				for (String t : new String[]{resolveTypedef(f.type), resolveTypedef(f.entryType)})
					if (t != null && DURATIONS.containsKey(t)) usedDurations.computeIfAbsent(t, k -> reserve(DURATIONS.get(k)[0]));
			}

			// ── file ──
			AdHocWriter.fileHeader(sb, "Matter2AdHoc", c.file + (globals.structs.isEmpty() && globals.enums.isEmpty() ? "" : " + globals"),
					"Matter cluster " + c.displayName + " id " + c.id + " revision " + c.revision);
			sb.append("namespace org.matter {\n");
			Map<String, Integer> dash = new LinkedHashMap<>();
			for (String n : reqName.values()) dash.put(n, null);
			for (String n : respName.values()) dash.put(n, null);
			for (String n : eventName.values()) dash.put(n, null);
			dash.put(attrsName, null);
			for (Struct s : structs.values()) dash.put(typeNames.get(s.name), null);
			for (String n : octstrTypedefs.values()) dash.put(n, null);
			AdHocWriter.dashboard(sb, I1, dash);
			sb.append(I1).append("public interface ").append(c.project).append(" {\n\n");

			// Matter bounds a collection only where the spec states one; AdHoc's own default of 255 would truncate
			// the rest, so the project default is raised and per-field [D(...)] carries the bounds the spec does give.
			sb.append(I2).append("enum _DefaultMaxLengthOf {\n");
			sb.append(I3).append("Arrays  = 65_535,\n");
			sb.append(I3).append("Maps    = 65_535,\n");
			sb.append(I3).append("Sets    = 65_535,\n");
			sb.append(I3).append("Strings = 65_535,\n");
			sb.append(I2).append("}\n\n");

			// cluster info
			StringBuilder d = new StringBuilder("Matter cluster \"" + c.displayName + "\" (id " + c.id + ", revision " + c.revision + ").");
			for (String h : c.history) d.append('\n').append(h);
			doc(sb, I2, d.toString());
			sb.append(I2).append("public struct ").append(infoName).append(" {\n");
			sb.append(I3).append("public const uint cluster_id = ").append(hex(c.id)).append(";\n");
			sb.append(I3).append("public const string cluster_name = ").append(str(nz(c.displayName))).append(";\n");
			sb.append(I3).append("public const uint revision = ").append(nz(c.revision).isEmpty() ? "0" : c.revision).append(";\n");
			sb.append(I2).append("}\n\n");

			// features
			if (!c.features.isEmpty()) {
				if (c.features.size() >= 2) {
					doc(sb, I2, "FeatureMap bits of the cluster.");
					sb.append(I2).append("[Flags]\n").append(I2).append("enum ").append(featuresName).append(" {\n");
					Set<String> used = new HashSet<>();
					for (Feature f : c.features) {
						doc(sb, I3, nz(f.summary) + " (code " + f.code + ")");
						sb.append(I3).append(AdHocWriter.unique(f.name, used)).append(" = ").append(1L << f.bit).append(",\n");
					}
					sb.append(I2).append("}\n\n");
				} else {
					sb.append(I2).append("// single feature: AdHoc rejects one-member enums, kept as a constants container\n");
					sb.append(I2).append("public struct ").append(featuresName).append(" {\n");
					for (Feature f : c.features) {
						doc(sb, I3, nz(f.summary) + " (code " + f.code + ")");
						sb.append(I3).append("public const uint ").append(ident(f.name)).append(" = ").append(1L << f.bit).append(";\n");
					}
					sb.append(I2).append("}\n\n");
				}
			}

			// enums
			for (EnumT e : enums.values()) emitEnum(e);
			for (Bitmap b : bitmaps.values()) emitBitmap(b);
			for (Struct s : structs.values()) emitStruct(s);
			// Duration aliases: one declaration per Matter elapsed/monotonic type, shared by every field of it.
			for (Map.Entry<String, String> t : usedDurations.entrySet()) {
				String[] spec = DURATIONS.get(t.getKey());
				doc(sb, I2, spec[3]);
				sb.append(I2).append("class ").append(t.getValue()).append(" : Duration {\n");
				sb.append(I3).append("public long     max       => ").append(spec[2]).append(";\n");
				sb.append(I3).append("public TimeSpan precision => ").append(spec[1]).append(";\n");
				sb.append(I2).append("}\n\n");
			}
			for (Map.Entry<String, String> t : octstrTypedefs.entrySet()) {
				// A TYPEDEF of a Binary list cannot be a list item (the agent rejects the nesting), so each octet
				// string of a list<octstr> is wrapped in a small sub-pack instead.
				boolean any = t.getKey().equals("any");
				sb.append(I2).append("// octet string ").append(any ? "of unstated length (takes _DefaultMaxLengthOf.Arrays)" : "of up to " + t.getKey() + " bytes")
						.append(", item type of list&lt;octstr&gt; fields\n".replace("&lt;", "<").replace("&gt;", ">"));
				sb.append(I2).append("class ").append(t.getValue()).append(" { ");
				if (!any) sb.append("[D(").append(t.getKey()).append(")] ");
				sb.append("Binary[,,] bytes; }\n\n");
			}

			// attributes pack
			sb.append(I2).append("// ═════════════════════════ attributes ═════════════════════════\n\n");
			doc(sb, I2, "All attributes of the cluster as one pack: the server reports them, the client writes the writable ones.");
			sb.append(I2).append("class ").append(attrsName).append(" {\n");
			Set<String> fieldsTaken = new HashSet<>();
			fieldsTaken.add(attrsName);
			for (Attr a : c.attrs) emitField(a, fieldsTaken, true);
			sb.append(I2).append("}\n\n");

			// commands
			if (!c.commands.isEmpty()) sb.append(I2).append("// ═════════════════════════ commands ═════════════════════════\n\n");
			for (Command cmd : c.commands) {
				String name = reqName.containsKey(cmd) ? reqName.get(cmd) : respName.get(cmd);
				if (name == null) continue;
				String kind = "responseFromServer".equals(cmd.direction) ? "response" : rpc.containsKey(cmd) ? "request, answered by " + respName.get(rpc.get(cmd)) : "command (acknowledged with a status only)";
				doc(sb, I2, "Matter " + kind + " " + cmd.name + " (id " + cmd.id + ")");
				sb.append(I2).append("class ").append(name).append(" {\n");
				sb.append(I3).append("public const uint command_id = ").append(hex(cmd.id)).append(";\n");
				if (!cmd.access.isEmpty()) sb.append(I3).append("public const string access = ").append(str(cmd.access)).append(";\n");
				if (!cmd.conformance.isEmpty()) sb.append(I3).append("public const string conformance = ").append(str(cmd.conformance)).append(";\n");
				Set<String> ft = new HashSet<>();
				ft.add(name);
				for (Field f : cmd.fields) emitField(f, ft, false);
				sb.append(I2).append("}\n\n");
			}

			// events
			if (!c.events.isEmpty()) sb.append(I2).append("// ═════════════════════════ events ═════════════════════════\n\n");
			for (Event ev : c.events) {
				doc(sb, I2, "Matter event " + ev.name + " (id " + ev.id + ", priority " + ev.priority + ")");
				sb.append(I2).append("class ").append(eventName.get(ev)).append(" {\n");
				sb.append(I3).append("public const uint event_id = ").append(hex(ev.id)).append(";\n");
				sb.append(I3).append("public const string priority = ").append(str(nz(ev.priority))).append(";\n");
				if (!ev.access.isEmpty()) sb.append(I3).append("public const string access = ").append(str(ev.access)).append(";\n");
				if (!ev.conformance.isEmpty()) sb.append(I3).append("public const string conformance = ").append(str(ev.conformance)).append(";\n");
				Set<String> ft = new HashSet<>();
				ft.add(eventName.get(ev));
				for (Field f : ev.fields) emitField(f, ft, false);
				sb.append(I2).append("}\n\n");
			}

			// topology
			sb.append(I2).append("// ═════════════════════════ topology ═════════════════════════\n\n");
			AdHocWriter.host(sb, I2, "Client", "The Matter client / controller (invokes commands, reads and writes attributes, receives events).");
			AdHocWriter.host(sb, I2, "Server", "The Matter server / device that implements the cluster.");
			AdHocWriter.connectionOpen(sb, I2, "Interaction", "Client", "Server");
			connTaken.addAll(Arrays.asList("Invoke", "Report", "AttributeAccess", "Interaction"));
			List<String> ff = new ArrayList<>();
			for (Command cmd : fireAndForget) ff.add(reqName.get(cmd));
			if (!ff.isEmpty()) {
				sb.append(I3).append("// commands acknowledged with a status only: fire-and-forget from the client\n");
				AdHocWriter.statePacks(sb, I3, "l____________", "Invoke", ff);
				sb.append('\n');
			}
			if (!rpc.isEmpty()) sb.append(I3).append("// commands with a data response: request/response calls initiated by the client\n");
			for (Map.Entry<Command, Command> e : rpc.entrySet()) {
				String method = AdHocWriter.unique(e.getKey().name, connTaken);
				sb.append(I3).append("(L____________, ").append(respName.get(e.getValue())).append(") ").append(method).append("(").append(reqName.get(e.getKey())).append(" req);\n");
			}
			List<String> orphanResponses = new ArrayList<>();
			for (Command cmd : c.commands)
				if ("responseFromServer".equals(cmd.direction) && !rpc.containsValue(cmd)) orphanResponses.add(respName.get(cmd));
			List<String> evs = new ArrayList<>(eventName.values());
			evs.addAll(orphanResponses);
			if (!evs.isEmpty()) {
				sb.append('\n').append(I3).append("// events (and unreferenced responses) are reported by the server\n");
				AdHocWriter.statePacks(sb, I3, "____________r", "Report", evs);
			}
			sb.append('\n').append(I3).append("// attribute reads / writes / subscriptions carry the attributes pack in both directions\n");
			AdHocWriter.statePacks(sb, I3, "_____lr_____", "AttributeAccess", Arrays.asList(attrsName));
			sb.append(I2).append("}\n\n");

			// custom attributes
			sb.append(I2).append("// ═════════════════════════ Matter metadata attributes ═════════════════════════\n\n");
			AdHocWriter.attribute(sb, I2, "FieldId", "Matter field id inside a struct, command or event.", "uint id");
			AdHocWriter.attribute(sb, I2, "AttrId", "Matter attribute id.", "uint id");
			AdHocWriter.attribute(sb, I2, "Access", "Access as in the data model: read:<privilege> write:<privilege> invoke:<privilege> timed.", "string access");
			AdHocWriter.attribute(sb, I2, "Quality", "Qualities: nullable, fixed/nonVolatile persistence, scene, quieterReporting, atomicWrite.", "string quality");
			AdHocWriter.attribute(sb, I2, "Default", "Default value as written in the data model.", "string value");
			AdHocWriter.attribute(sb, I2, "MatterType", "The Matter type name when it is not a plain integer/string (semantic types, enum8, map8, list entry types...).", "string type");
			AdHocWriter.attribute(sb, I2, "Constraint", "Constraint as written in the data model (max, between a..b, maxLength, allowed, attr:Name references, desc).", "string constraint");
			AdHocWriter.attribute(sb, I2, "Conformance", "Conformance: M mandatory, O optional, P provisional, X obsolete/disallowed, [..] feature conditions, otherwise(..) chains.", "string conformance");
			sb.append(I1).append("}\n");
			sb.append("}\n");
			for (String n : notes) System.out.println("  note " + c.file + ": " + n);
			return sb.toString();
		}

		/** Local types plus the global types the cluster references (transitively through structs and list entries). */
		void collectTypes() {
			enums.putAll(c.enums);
			bitmaps.putAll(c.bitmaps);
			structs.putAll(c.structs);
			boolean changed = true;
			while (changed) {
				changed = false;
				for (Field f : allFields()) {
					for (String t : new String[]{resolveTypedef(f.type), resolveTypedef(f.entryType)}) {
						if (t == null || enums.containsKey(t) || bitmaps.containsKey(t) || structs.containsKey(t)) continue;
						if (globals.enums.containsKey(t)) { enums.put(t, globals.enums.get(t)); changed = true; }
						else if (globals.bitmaps.containsKey(t)) { bitmaps.put(t, globals.bitmaps.get(t)); changed = true; }
						else if (globals.structs.containsKey(t)) { structs.put(t, globals.structs.get(t)); changed = true; }
					}
				}
			}
		}

		List<Field> allFields() {
			List<Field> all = new ArrayList<>(c.attrs);
			for (Command x : c.commands) all.addAll(x.fields);
			for (Event x : c.events) all.addAll(x.fields);
			for (Struct s : structs.values()) all.addAll(s.fields);
			return all;
		}

		String resolveTypedef(String t) {
			if (t == null) return null;
			String r = c.typedefs.getOrDefault(t, globals.typedefs.get(t));
			return r != null ? r : t;
		}

		String reserve(String raw) { return AdHocWriter.unique(raw, taken); }

		String octstrTypedef(int max) {
			// No stated length: the sub-pack takes _DefaultMaxLengthOf.Arrays rather than a made-up cap.
			return octstrTypedefs.computeIfAbsent(max < 0 ? "any" : String.valueOf(max),
					k -> reserve(k.equals("any") ? "octstr" : "octstr_max_" + k));
		}

		void emitEnum(EnumT e) {
			String name = typeNames.get(e.name);
			String origin = e.origin.equals(c.file) ? "" : " (from " + e.origin + ")";
			if (e.items.size() < 2) {
				containers.add(e.name);
				sb.append(I2).append("// enum with fewer than two items").append(origin).append(": AdHoc rejects such enums, kept as a constants container\n");
				sb.append(I2).append("public struct ").append(name).append(" {\n");
				Set<String> used = new HashSet<>();
				for (String[] it : e.items) {
					doc(sb, I3, nz(it[2]));
					sb.append(I3).append("public const int ").append(AdHocWriter.unique(it[1], used)).append(" = ").append(it[0]).append(";\n");
				}
				if (e.items.isEmpty()) sb.append(I3).append("public const bool EMPTY = true;\n");
				sb.append(I2).append("}\n\n");
				return;
			}
			doc(sb, I2, "Matter enum " + e.name + origin);
			sb.append(I2).append("enum ").append(name).append(" {\n");
			Set<String> used = new HashSet<>();
			for (String[] it : e.items) {
				doc(sb, I3, nz(it[2]));
				sb.append(I3).append(AdHocWriter.unique(it[1], used));
				if (it[0] != null) sb.append(" = ").append(it[0]);
				sb.append(",\n");
			}
			sb.append(I2).append("}\n\n");
		}

		void emitBitmap(Bitmap b) {
			String name = typeNames.get(b.name);
			String origin = b.origin.equals(c.file) ? "" : " (from " + b.origin + ")";
			int maxBit = 0;
			for (Object[] bf : b.bits) maxBit = Math.max(maxBit, (Integer) bf[2]);
			String underlying = maxBit >= 63 ? " : ulong" : maxBit >= 31 ? " : long" : "";
			if (b.bits.size() < 2) {
				containers.add(b.name);
				sb.append(I2).append("// bitmap with a single field").append(origin).append(": AdHoc rejects one-member enums, kept as a constants container\n");
				sb.append(I2).append("public struct ").append(name).append(" {\n");
				Set<String> used = new HashSet<>();
				for (Object[] bf : b.bits) {
					doc(sb, I3, nz((String) bf[3]));
					sb.append(I3).append("public const ulong ").append(AdHocWriter.unique((String) bf[0], used)).append(" = ").append(mask((Integer) bf[1], (Integer) bf[2])).append(";\n");
				}
				if (b.bits.isEmpty()) sb.append(I3).append("public const bool EMPTY = true;\n");
				sb.append(I2).append("}\n\n");
				return;
			}
			doc(sb, I2, "Matter bitmap " + b.name + origin + (maxBit > 7 ? " (map" + (maxBit > 31 ? 64 : maxBit > 15 ? 32 : 16) + ")" : " (map8)"));
			sb.append(I2).append("[Flags]\n").append(I2).append("enum ").append(name).append(underlying).append(" {\n");
			Set<String> used = new HashSet<>();
			for (Object[] bf : b.bits) {
				int lo = (Integer) bf[1], hi = (Integer) bf[2];
				doc(sb, I3, nz((String) bf[3]) + (lo != hi ? " (bits " + lo + ".." + hi + ")" : ""));
				sb.append(I3).append(AdHocWriter.unique((String) bf[0], used)).append(" = ").append(mask(lo, hi)).append(",\n");
			}
			sb.append(I2).append("}\n\n");
		}

		static String mask(int lo, int hi) {
			long m = hi - lo >= 63 ? -1L : (((1L << (hi - lo + 1)) - 1) << lo);
			return m < 0 ? "0x" + Long.toHexString(m) + "UL" : hi > 30 ? "0x" + Long.toHexString(m) : Long.toString(m);
		}

		void emitStruct(Struct s) {
			String name = typeNames.get(s.name);
			doc(sb, I2, "Matter struct " + s.name + (s.origin.equals(c.file) ? "" : " (from " + s.origin + ")"));
			sb.append(I2).append("class ").append(name).append(" {\n");
			Set<String> ft = new HashSet<>();
			ft.add(name);
			for (Field f : s.fields) emitField(f, ft, false);
			sb.append(I2).append("}\n\n");
		}

		/** One field or attribute line with its metadata attributes. */
		void emitField(Field f, Set<String> fieldsTaken, boolean isAttribute) {
			String srcType = resolveTypedef(f.type);
			List<String> attrs = new ArrayList<>();
			String cs;
			boolean valueType = true;
			List<String> comments = new ArrayList<>();

			if ("list".equals(srcType)) {
				String et = resolveTypedef(f.entryType);
				valueType = false;
				String item;
				List<String> itemAttrs = new ArrayList<>(); // only a MatterType, already covered by the list<...> attribute below
				if (et == null) { item = "int"; comments.add("list without entry type"); }
				else if ("octstr".equals(et)) item = octstrTypedef(f.entryMaxLength);
				else if ("string".equals(et)) { item = "string"; }
				else if ("list".equals(et)) { item = "int"; comments.add("nested list not supported"); }
				else item = fieldType(et, itemAttrs, comments);
				List<String> dims = new ArrayList<>();
				if ("string".equals(et) && f.entryMaxLength > 0) dims.add("+" + f.entryMaxLength);
				if (f.maxCount > 0) dims.add(String.valueOf(f.maxCount));
				else if (f.maxCount < 0 && !f.constraint.isEmpty() && f.constraint.contains("attr:"))
					comments.add("item count is bounded by another attribute; falls back to _DefaultMaxLengthOf.Arrays");
				if (!dims.isEmpty()) attrs.add(0, "D(" + String.join(", ", dims) + ")");
				cs = item + "[,,]";
				if (et != null && !DURATIONS.containsKey(et) && !DATETIME.contains(et)) attrs.add("MatterType(" + str("list<" + et + ">") + ")");
				if (!f.entryConstraint.isEmpty()) comments.add("entry constraint: " + f.entryConstraint);
			} else if ("string".equals(srcType)) {
				valueType = false;
				cs = "string";
				if (f.maxLength > 0) attrs.add("D(+" + f.maxLength + ")");
			} else if ("octstr".equals(srcType)) {
				valueType = false;
				cs = "Binary[,,]";
				if (f.maxLength > 0) attrs.add("D(" + f.maxLength + ")");
			} else {
				cs = fieldType(srcType, attrs, comments);
				valueType = !structs.containsKey(srcType) || containers.contains(srcType);
				long[] mm = minMax(f, cs, srcType);
				if (mm != null) attrs.add("MinMax(" + mm[0] + ", " + mm[1] + ")");
				else if (f.symbolicBound) comments.add("bounds depend on another attribute, cannot be bit-packed");
				String hint = physics(f.name, cs, mm != null);
				if (hint != null) comments.add(hint);
			}
			if (valueType && (f.nullable || f.optional)) cs += "?";

			if (f.id != null) attrs.add((isAttribute ? "AttrId(" : "FieldId(") + hex(f.id) + ")");
			if (isAttribute) {
				Attr a = (Attr) f;
				if (!a.access.isEmpty()) attrs.add("Access(" + str(a.access) + ")");
				if (!a.quality.isEmpty()) attrs.add("Quality(" + str(a.quality) + ")");
			} else if (f.nullable) attrs.add("Quality(\"nullable\")");
			if (f.dflt != null) attrs.add("Default(" + str(f.dflt) + ")");
			if (!f.constraint.isEmpty()) attrs.add("Constraint(" + str(f.constraint) + ")");
			if (!f.conformance.isEmpty() && !f.conformance.equals("M")) attrs.add("Conformance(" + str(f.conformance) + ")");

			String fname = AdHocWriter.unique(f.name, fieldsTaken);
			doc(sb, I3, f.summary);
			sb.append(I3);
			if (!attrs.isEmpty()) sb.append("[").append(String.join(", ", attrs)).append("] ");
			sb.append(cs).append(' ').append(fname).append(';');
			if (!comments.isEmpty()) sb.append(" // ").append(String.join("; ", comments));
			sb.append('\n');
		}

		/**
		 * The hard range to bit-pack the field into, or null when the source states none. Matter constraints are
		 * hard limits (a value outside them is a protocol error), so they belong in `[MinMax]` rather than in a
		 * metadata string. `percent` and `percent100ths` carry their range in the type itself.
		 */
		long[] minMax(Field f, String cs, String srcType) {
			if (!INTEGER.contains(cs)) return null;
			Long lo = null, hi = null;
			if (f.range != null) { lo = f.range[0]; hi = f.range[1]; }
			else { lo = f.cMin; hi = f.cMax; }
			if (lo == null && hi == null) {
				if ("percent".equals(srcType)) { lo = 0L; hi = 100L; }
				else if ("percent100ths".equals(srcType)) { lo = 0L; hi = 10_000L; }
				else return null;
			}
			long[] bounds = typeBounds(cs);
			if (bounds == null) return lo != null && hi != null && lo < hi ? new long[]{lo, hi} : null; // ulong
			if (lo == null) lo = bounds[0];
			if (hi == null) hi = bounds[1];
			lo = Math.max(lo, bounds[0]);
			hi = Math.min(hi, bounds[1]);
			if (lo >= hi) return null;                                   // empty or single-valued range
			if (lo == bounds[0] && hi == bounds[1]) return null;          // the declared type already says this
			return new long[]{lo, hi};
		}

		/**
		 * Where the values of a field actually sit, when the cluster XML says enough to tell. Matter never states a
		 * distribution directly, but a conventional attribute name often implies one. The result is only ever a
		 * comment naming a candidate: choosing varint is the developer's call and a converter cannot take it, but
		 * it must not drop the question either.
		 *
		 * <p>Only for integers wider than one byte with no hard range; a `[MinMax]` field is already bit-packed.
		 */
		static String physics(String name, String cs, boolean hasHardRange) {
			if (hasHardRange || !INTEGER.contains(cs) || cs.equals("byte") || cs.equals("sbyte")) return null;
			String lower = name == null ? "" : name.toLowerCase();
			if (lower.startsWith("remaining") || lower.endsWith("remaining"))
				return "physics: a remaining budget hugs its ceiling -> consider [V(max)]";
			if (lower.endsWith("count") || lower.endsWith("counter") || lower.startsWith("numberof")
					|| lower.endsWith("index") || lower.endsWith("sequence"))
				return "physics: counter/index, floor at 0, unbounded above -> consider [A]";
			if (lower.contains("delta") || lower.contains("offset") || lower.contains("deviation")
					|| lower.contains("correction") || lower.contains("drift"))
				return "physics: a delta centred on zero -> consider [X(amplitude)]";
			return null;
		}

		/** C# type for a non-collection Matter type; adds a [MatterType] attribute for anything that is not a plain base type. */
		String fieldType(String t, List<String> attrs, List<String> comments) {
			// Temporal types map to AdHoc's own time concepts, not to an integer plus a [MatterType] tag.
			if (DATETIME.contains(t)) {
				// A microsecond / millisecond epoch is systematically past 268_435_455, where varint always loses;
				// DateTime carries the instant directly instead of a large integer.
				comments.add("Matter " + t + (t.endsWith("-us") || t.endsWith("-ms")
						? "; physics: monotonic and huge, varint would always lose here" : ""));
				return "DateTime";
			}
			if (DURATIONS.containsKey(t)) {
				comments.add("Matter " + t + ("elapsed-s".equals(t)
						? "; physics: timeouts cluster near zero ([A] shape), Duration already sizes it to the smallest container"
						: "; physics: monotonic and huge, varint would always lose here"));
				return usedDurations.computeIfAbsent(t, k -> reserve(DURATIONS.get(k)[0]));
			}
			if (enums.containsKey(t) || bitmaps.containsKey(t)) {
				if (containers.contains(t)) {
					attrs.add("MatterType(" + str(t) + ")");
					comments.add("values: constants container " + typeNames.get(t));
					return bitmaps.containsKey(t) ? bitmapBase(bitmaps.get(t)) : enumBase(enums.get(t));
				}
				return typeNames.get(t);
			}
			if (structs.containsKey(t)) return typeNames.get(t);
			String base = BASE.get(t);
			if (base != null) {
				if (!PRIMITIVE_BASE.contains(t)) attrs.add("MatterType(" + str(t) + ")");
				return base;
			}
			attrs.add("MatterType(" + str(t) + ")");
			comments.add("unresolved Matter type " + t);
			return "long";
		}

		static String bitmapBase(Bitmap b) {
			int maxBit = 0;
			for (Object[] bf : b.bits) maxBit = Math.max(maxBit, (Integer) bf[2]);
			return maxBit < 8 ? "byte" : maxBit < 16 ? "ushort" : maxBit < 32 ? "uint" : "ulong";
		}

		static String enumBase(EnumT e) {
			long max = 0;
			for (String[] it : e.items) try { max = Math.max(max, Long.decode(it[0])); } catch (Exception ignored) { }
			return max < 256 ? "byte" : "ushort";
		}

		static String hex(String id) {
			if (id == null) return "0";
			String s = id.trim();
			try { return "0x" + Long.toHexString(Long.decode(s)).toUpperCase(); } catch (NumberFormatException e) { return "0"; }
		}
	}
}
