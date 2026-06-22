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
 *   <points>
 *     <point name="pump_run"   kind="COIL"             address="0"/>
 *     <point name="tank_level" kind="HOLDING_REGISTER" address="100"/>
 *   </points>
 * </config>
 * }</pre>
 *
 * <p>Read-only for now; a save path will follow when the web UI gains config editing.
 * The {@code kind} attribute accepts the singular forms shown above (the names an
 * operator naturally writes) and maps them to {@link RegisterKind}.
 */
public final class ConfigStore
{
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

		Element web = requireChild(doc.getDocumentElement(), "webserver");
		String webHost = text(web, "ip");
		int webPort = intOf(web, "port");
		if (webPort < 1 || webPort > 65535)
		{
			throw new IllegalArgumentException("webserver port out of range (1..65535): " + webPort);
		}

		List<ModbusPoint> points = readPoints(doc);
		if (points.isEmpty())
		{
			throw new IllegalArgumentException("config defines no <point> entries");
		}

		return new BridgeConfig(host, port, unitId, pollIntervalMs, webHost, webPort, points);
	}

	private static List<ModbusPoint> readPoints(Document doc)
	{
		List<ModbusPoint> points = new ArrayList<>();
		List<String> seen = new ArrayList<>();
		NodeList nodes = doc.getElementsByTagName("point");
		for (int i = 0; i < nodes.getLength(); i++)
		{
			Element e = (Element) nodes.item(i);
			String name = attr(e, "name");
			RegisterKind kind = parseKind(attr(e, "kind"), name);
			int address = Integer.parseInt(attr(e, "address").trim());
			if (seen.contains(name))
			{
				throw new IllegalArgumentException("duplicate point name: " + name);
			}
			seen.add(name);
			points.add(new ModbusPoint(name, kind, address));
		}
		return points;
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
		return Integer.parseInt(text(parent, tag));
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
