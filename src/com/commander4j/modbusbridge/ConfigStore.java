package com.commander4j.modbusbridge;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.commander4j.modbus.RegisterKind;

/**
 * Reads the bridge's extended XML configuration:
 * <pre>{@code
 * <config>
 *   <modbus>    <ip>192.168.1.50</ip> <port>502</port> <id>1</id>
 *               <pollIntervalMs>500</pollIntervalMs> </modbus>
 *   <webserver> <ip>0.0.0.0</ip> <port>8080</port> </webserver>
 *   <restapi>
 *     <id name="LANEA" point="pump_run" caseSensitive="false"/>
 *     <id name="LANEB" point="pump_run" caseSensitive="false"/>
 *   </restapi>
 *   <points>
 *     <point name="pump_run"   kind="COIL"             address="0"/>
 *     <point name="tank_level" kind="HOLDING_REGISTER" address="100"/>
 *   </points>
 * </config>
 * }</pre>
 *
 * <p>Read-only for now; a save path will follow when the web UI gains config editing.
 * The {@code kind} attribute accepts the singular forms shown above (the names an
 * operator naturally writes) and maps them to {@link RegisterKind}. The optional
 * {@code <restapi>} section maps application-facing ids onto points (see {@link RestApiId});
 * ids and point names share one namespace, validated here.
 */
public final class ConfigStore
{
	/** Default cap on a pulse's hold time when {@code <maxHoldMs>} is absent (1 minute). */
	static final int DEFAULT_MAX_HOLD_MS = 60000;

	/**
	 * Default pulse hold pre-fill when a point has no {@code defaultHoldMs} attribute —
	 * sized for switching an electrical relay. Clamped into the point's min/max range.
	 */
	static final int DEFAULT_HOLD_MS = 3000;

	private ConfigStore()
	{
	}

	public static BridgeConfig load(File file) throws Exception
	{
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = dbf.newDocumentBuilder();
		Document doc = builder.parse(file);
		doc.getDocumentElement().normalize();

		Element modbus = requireChild(doc.getDocumentElement(), "modbus");
		boolean modbusEnabled = enabledAttr(modbus);
		String host = text(modbus, "ip");
		int port = intOf(modbus, "port");
		int unitId = intOf(modbus, "id");
		int pollIntervalMs = intOf(modbus, "pollIntervalMs");

		if (port < 1 || port > 65535)
		{
			throw new IllegalArgumentException("modbus port out of range (1..65535): " + port);
		}
		if (unitId < 0 || unitId > 247)
		{
			throw new IllegalArgumentException("modbus id out of range (0..247): " + unitId);
		}
		if (pollIntervalMs < 50)
		{
			throw new IllegalArgumentException("pollIntervalMs too small (min 50): " + pollIntervalMs);
		}

		// Optional: existing config.xml files predate the pulse endpoint, so default it.
		int maxHoldMs = intOpt(modbus, "maxHoldMs", DEFAULT_MAX_HOLD_MS);
		if (maxHoldMs < 1)
		{
			throw new IllegalArgumentException("maxHoldMs must be at least 1 ms: " + maxHoldMs);
		}

		Element web = requireChild(doc.getDocumentElement(), "webserver");
		String webHost = text(web, "ip");
		int webPort = intOf(web, "port");
		if (webPort < 1 || webPort > 65535)
		{
			throw new IllegalArgumentException("webserver port out of range (1..65535): " + webPort);
		}

		List<ModbusPoint> points = readPoints(doc, maxHoldMs);
		if (points.isEmpty())
		{
			throw new IllegalArgumentException("config defines no <point> entries");
		}

		List<RestApiId> restIds = readRestIds(doc, points);

		return new BridgeConfig(host, port, unitId, pollIntervalMs, modbusEnabled, webHost, webPort, maxHoldMs, points, restIds);
	}

	/**
	 * Reads the optional {@code <restapi>} section:
	 * {@code <id name="LANEA" point="PLC_Link1" caseSensitive="false"/>}. Each id must
	 * reference an existing point by name. Ids and point names share one namespace: exact
	 * duplicates are rejected, and a case-<em>insensitive</em> id (the default) reserves its
	 * whole case class, so nothing else may differ from it only by capitalisation. Two
	 * case-sensitive entries differing only in case remain legal.
	 *
	 * <p>Scoped to the {@code <restapi>} element because {@code <modbus>} also has an
	 * {@code <id>} child — a document-wide tag search would swallow the unit id.
	 */
	private static List<RestApiId> readRestIds(Document doc, List<ModbusPoint> points)
	{
		List<RestApiId> ids = new ArrayList<>();
		NodeList sections = doc.getElementsByTagName("restapi");
		if (sections.getLength() == 0)
		{
			return ids;
		}

		List<String> exact = new ArrayList<>();   // every identifier, as written
		List<String> folded = new ArrayList<>();  // every identifier, lower-cased
		List<String> foldedInsensitive = new ArrayList<>(); // case classes claimed by insensitive ids
		List<String> pointNames = new ArrayList<>();
		for (ModbusPoint p : points)
		{
			pointNames.add(p.name());
			exact.add(p.name());
			folded.add(fold(p.name()));
		}

		NodeList nodes = ((Element) sections.item(0)).getElementsByTagName("id");
		for (int i = 0; i < nodes.getLength(); i++)
		{
			Element e = (Element) nodes.item(i);
			String name = idAttr(e, "name");
			String point = idAttr(e, "point");
			boolean caseSensitive = caseSensitiveAttr(e, name);

			if (!pointNames.contains(point))
			{
				throw new IllegalArgumentException("restapi id '" + name + "' references unknown point: " + point);
			}
			if (exact.contains(name))
			{
				throw new IllegalArgumentException("duplicate name: '" + name + "' is already a point name or restapi id");
			}
			if (foldedInsensitive.contains(fold(name)))
			{
				throw new IllegalArgumentException("restapi id '" + name + "' collides with a case-insensitive id differing only in case");
			}
			if (!caseSensitive && folded.contains(fold(name)))
			{
				throw new IllegalArgumentException("case-insensitive restapi id '" + name + "' collides with an existing name differing only in case");
			}

			exact.add(name);
			folded.add(fold(name));
			if (!caseSensitive)
			{
				foldedInsensitive.add(fold(name));
			}
			ids.add(new RestApiId(name, point, caseSensitive));
		}
		return ids;
	}

	private static String fold(String name)
	{
		return name.toLowerCase(java.util.Locale.ROOT);
	}

	/** Parses the optional {@code caseSensitive} attribute on an {@code <id>}; absent → false. */
	private static boolean caseSensitiveAttr(Element e, String idName)
	{
		String raw = e.getAttribute("caseSensitive").trim();
		if (raw.isEmpty())
		{
			raw = e.getAttribute("casesensitive").trim(); // accept the all-lowercase spelling too
		}
		return switch (raw.toLowerCase(java.util.Locale.ROOT))
		{
			case "", "false" -> false;
			case "true" -> true;
			default -> throw new IllegalArgumentException("restapi id '" + idName + "' has invalid caseSensitive (expected true/false): " + raw);
		};
	}

	private static String idAttr(Element e, String name)
	{
		String v = e.getAttribute(name);
		if (v == null || v.isBlank())
		{
			throw new IllegalArgumentException("restapi <id> missing '" + name + "' attribute");
		}
		return v.trim();
	}

	private static List<ModbusPoint> readPoints(Document doc, int globalMaxHoldMs)
	{
		List<ModbusPoint> points = new ArrayList<>();
		List<String> seen = new ArrayList<>();
		NodeList nodes = doc.getElementsByTagName("point");
		for (int i = 0; i < nodes.getLength(); i++)
		{
			Element e = (Element) nodes.item(i);
			String name = attr(e, "name");
			RegisterKind kind = parseKind(attr(e, "kind"), name);
			int address;
			try
			{
				address = Integer.parseInt(attr(e, "address").trim());
			}
			catch (NumberFormatException nfe)
			{
				throw new IllegalArgumentException("point '" + name + "' address must be an integer: " + attr(e, "address"));
			}
			if (seen.contains(name))
			{
				throw new IllegalArgumentException("duplicate point name: " + name);
			}
			seen.add(name);

			boolean writable = kind == RegisterKind.COILS || kind == RegisterKind.HOLDING_REGISTERS;

			// Optional simulation: simulate="true" detaches the point from the wire — reads,
			// writes and pulses behave normally but touch memory only. value seeds it (default 0).
			boolean simulate = parseBool(e.getAttribute("simulate"), "simulate", name);

			// Optional startup initialisation: initialise="true" value="..."
			boolean initialise = parseBool(e.getAttribute("initialise"), "initialise", name);
			if (simulate && initialise)
			{
				throw new IllegalArgumentException("point '" + name + "' cannot combine simulate=\"true\" with initialise=\"true\"");
			}
			int initialValue = 0;
			if (initialise)
			{
				if (!writable)
				{
					throw new IllegalArgumentException("point '" + name + "' cannot be initialised: " + kind.name() + " is read-only");
				}
				String raw = e.getAttribute("value").trim();
				if (raw.isEmpty())
				{
					throw new IllegalArgumentException("point '" + name + "' has initialise=\"true\" but no value attribute");
				}
				initialValue = parseValueAttr(kind, raw, name);
			}
			if (simulate)
			{
				String raw = e.getAttribute("value").trim();
				if (!raw.isEmpty())
				{
					initialValue = parseValueAttr(kind, raw, name);
				}
			}

			// Optional pulse opt-out: pulse="false" means this point must never be pulsed
			// (plain writes unaffected). Absent → true. Meaningless on read-only kinds.
			String rawPulse = e.getAttribute("pulse").trim();
			if (!rawPulse.isEmpty() && !writable)
			{
				throw new IllegalArgumentException("point '" + name + "' cannot carry a pulse attribute: " + kind.name() + " is read-only");
			}
			boolean pulseAllowed = rawPulse.isEmpty() || parseBool(rawPulse, "pulse", name);

			// Optional per-point pulse hold policy: minHoldMs / maxHoldMs / defaultHoldMs.
			// Absent → min 1, max = the global <maxHoldMs>, default = 3000 clamped into range.
			// Explicit values are range-checked by the ModbusPoint invariants. They may coexist
			// with pulse="false" (kept-but-unused bounds, like value with initialise="false").
			int minHoldMs = 0;
			int maxHoldMs = 0;
			int defaultHoldMs = 0;
			boolean holdAttrs = !e.getAttribute("minHoldMs").isBlank() || !e.getAttribute("maxHoldMs").isBlank() || !e.getAttribute("defaultHoldMs").isBlank();
			if (holdAttrs && !writable)
			{
				throw new IllegalArgumentException("point '" + name + "' cannot carry pulse hold attributes: " + kind.name() + " is read-only");
			}
			if (writable)
			{
				minHoldMs = intAttr(e, "minHoldMs", 1, name);
				maxHoldMs = intAttr(e, "maxHoldMs", globalMaxHoldMs, name);
				int clampedDefault = Math.max(minHoldMs, Math.min(DEFAULT_HOLD_MS, maxHoldMs));
				defaultHoldMs = intAttr(e, "defaultHoldMs", clampedDefault, name);
			}

			points.add(new ModbusPoint(name, kind, address, simulate, initialise, initialValue, pulseAllowed, minHoldMs, maxHoldMs, defaultHoldMs));
		}
		return points;
	}

	/** Parses an optional integer attribute, returning {@code def} when absent/blank. */
	private static int intAttr(Element e, String name, int def, String pointName)
	{
		String raw = e.getAttribute(name).trim();
		if (raw.isEmpty())
		{
			return def;
		}
		try
		{
			return Integer.parseInt(raw);
		}
		catch (NumberFormatException ex)
		{
			throw new IllegalArgumentException("point '" + pointName + "' " + name + " must be an integer: " + raw);
		}
	}

	/** Parses an optional boolean attribute; blank → {@code false}. Accepts true/false only. */
	private static boolean parseBool(String raw, String attrName, String pointName)
	{
		String v = raw == null ? "" : raw.trim().toLowerCase();
		return switch (v)
		{
			case "", "false" -> false;
			case "true" -> true;
			default -> throw new IllegalArgumentException("point '" + pointName + "' has invalid " + attrName + " (expected true/false): " + raw);
		};
	}

	/**
	 * Parses the optional {@code enabled} attribute on {@code <modbus>}; absent/blank →
	 * {@code true} (existing configs predate the switch). {@code enabled="false"} stops the
	 * bridge connecting at all — real points stay stale, simulated points work regardless.
	 */
	private static boolean enabledAttr(Element modbus)
	{
		String v = modbus.getAttribute("enabled").trim().toLowerCase();
		return switch (v)
		{
			case "", "true" -> true;
			case "false" -> false;
			default -> throw new IllegalArgumentException("<modbus> enabled attribute must be true/false: " + modbus.getAttribute("enabled"));
		};
	}

	/**
	 * Parses the {@code value} attribute into a normalised int against the point's kind: bit
	 * kinds (coils, discrete inputs) accept {@code true}/{@code false}/{@code 1}/{@code 0} → 1/0;
	 * register kinds a 0..65535 integer. Used for both the initialise target (writable points
	 * only) and the simulate seed (any kind).
	 */
	private static int parseValueAttr(RegisterKind kind, String raw, String pointName)
	{
		if (kind.bit)
		{
			String v = raw.toLowerCase();
			return switch (v)
			{
				case "true", "1" -> 1;
				case "false", "0" -> 0;
				default -> throw new IllegalArgumentException("point '" + pointName + "' value must be true/false/1/0: " + raw);
			};
		}
		try
		{
			return Integer.parseInt(raw);
		}
		catch (NumberFormatException ex)
		{
			throw new IllegalArgumentException("point '" + pointName + "' register value must be an integer 0..65535: " + raw);
		}
	}

	/** Maps the operator-facing {@code kind} attribute to a {@link RegisterKind}. */
	private static RegisterKind parseKind(String raw, String pointName)
	{
		String k = raw.trim().toUpperCase().replace(' ', '_');
		return switch (k)
		{
			case "COIL", "COILS" -> RegisterKind.COILS;
			case "DISCRETE_INPUT", "DISCRETE_INPUTS" -> RegisterKind.DISCRETE_INPUTS;
			case "HOLDING_REGISTER", "HOLDING_REGISTERS" -> RegisterKind.HOLDING_REGISTERS;
			case "INPUT_REGISTER", "INPUT_REGISTERS" -> RegisterKind.INPUT_REGISTERS;
			default -> throw new IllegalArgumentException("point '" + pointName + "' has unknown kind: " + raw);
		};
	}

	private static Element requireChild(Element parent, String tag)
	{
		NodeList nodes = parent.getElementsByTagName(tag);
		if (nodes.getLength() == 0)
		{
			throw new IllegalArgumentException("Missing <" + tag + "> element");
		}
		return (Element) nodes.item(0);
	}

	private static String text(Element parent, String tag)
	{
		NodeList nodes = parent.getElementsByTagName(tag);
		if (nodes.getLength() == 0)
		{
			throw new IllegalArgumentException("Missing <" + tag + "> element");
		}
		String t = nodes.item(0).getTextContent();
		return t == null ? "" : t.trim();
	}

	private static int intOf(Element parent, String tag)
	{
		return parseIntElement(tag, text(parent, tag));
	}

	/** Reads an optional integer child element, returning {@code def} when it is absent. */
	private static int intOpt(Element parent, String tag, int def)
	{
		NodeList nodes = parent.getElementsByTagName(tag);
		if (nodes.getLength() == 0)
		{
			return def;
		}
		String t = nodes.item(0).getTextContent();
		if (t == null || t.isBlank())
		{
			return def;
		}
		return parseIntElement(tag, t.trim());
	}

	/** Parses an element's text as an int, naming the element in the error. */
	private static int parseIntElement(String tag, String text)
	{
		try
		{
			return Integer.parseInt(text);
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException("<" + tag + "> must be an integer: " + text);
		}
	}

	private static String attr(Element e, String name)
	{
		String v = e.getAttribute(name);
		if (v == null || v.isBlank())
		{
			throw new IllegalArgumentException("<point> missing '" + name + "' attribute");
		}
		return v;
	}
}
