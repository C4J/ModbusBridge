package com.commander4j.modbusbridge;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.commander4j.xml.JXMLDocument;

/**
 * Builds the third-party licence list for the bridge's About/licence endpoint.
 *
 * <p>This is a bridge-owned reimplementation of what the client's {@code JLicenseInfo}
 * did, and it exists for one specific reason: {@code JLicenseInfo}, compiled inside
 * {@code modbusClient.jar}, has the client's {@code Common.programName}/{@code version}
 * <b>inlined</b> as compile-time constants, so reusing it would forever report the
 * <em>client's</em> identity regardless of any runtime class shadow (JLS §13.4.9). Here
 * the app entry is built from {@link CommonBridge}, compiled into the bridge, so it
 * correctly reads "Commander4j Modbus Bridge 1.00".
 *
 * <p>{@link JXMLDocument} <em>is</em> reused from the jar — it references no {@code Common}
 * and so has nothing to inline, making it safe. It reads
 * {@code ./lib/license/LicenseInfo.xml} (American spelling, as the file is named).
 */
public final class Licences
{
	/** One row of the licence table. */
	public record Entry(String description, String version, String type, String licenceFilename)
	{
	}

	private static final String LICENSE_FILE = "." + File.separator + "lib" + File.separator + "license" + File.separator + "LicenseInfo.xml";

	private Licences()
	{
	}

	/** Returns the library licences from the XML plus the bridge's own app entry, sorted by description. */
	public static List<Entry> list()
	{
		JXMLDocument xml = new JXMLDocument(LICENSE_FILE);

		// type -> licence text filename, from the <licenses> section
		Map<String, String> licenceFiles = new HashMap<>();
		int i = 1;
		while (!xml.findXPath("//info/licenses/license[" + i + "]/type").trim().isEmpty())
		{
			String type = xml.findXPath("//info/licenses/license[" + i + "]/type").trim();
			String file = xml.findXPath("//info/licenses/license[" + i + "]/filename").trim();
			licenceFiles.put(type, file);
			i++;
		}

		List<Entry> entries = new ArrayList<>();
		i = 1;
		while (!xml.findXPath("//info/libraries/library[" + i + "]/description").trim().isEmpty())
		{
			String desc = xml.findXPath("//info/libraries/library[" + i + "]/description").trim();
			String type = xml.findXPath("//info/libraries/library[" + i + "]/type").trim();
			String ver = xml.findXPath("//info/libraries/library[" + i + "]/version").trim();
			entries.add(new Entry(desc, ver, type, licenceFiles.getOrDefault(type, "")));
			i++;
		}

		// The application itself — identity comes from the bridge, not an inlined jar constant.
		entries.add(new Entry(CommonBridge.programName, CommonBridge.version, "GNU General Public License V2", "GNU General Public License V2.txt"));

		entries.sort(Comparator.comparing(e -> e.description().toUpperCase()));
		return entries;
	}
}
