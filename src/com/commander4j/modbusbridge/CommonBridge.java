package com.commander4j.modbusbridge;

import java.io.File;

/**
 * Bridge identity and shared defaults.
 *
 * <p>This was originally placed at {@code com.commander4j.sys.Common} to <em>shadow</em>
 * the copy bundled in {@code modbusClient.jar}. That shadow is gone: it never helped the
 * reused jar code. The only jar class that referenced {@code Common} for identity —
 * {@code JLicenseInfo} — reads {@code programName}/{@code version} as compile-time
 * constants, which the Java compiler <b>inlined</b> into {@code JLicenseInfo.class} when
 * the jar was built (JLS §13.4.9). A runtime shadow cannot change an already-inlined
 * literal, so the jar's {@code JLicenseInfo} always emitted the client's identity. The
 * bridge therefore renders its own licence list (see {@link Licences}) and keeps its
 * identity in this plainly-named, bridge-package class. The reused {@code ClientController}
 * / {@code RegisterKind} / {@code JXMLDocument} never touch {@code Common} at all.
 */
public final class CommonBridge
{
	public static final String programName = "Commander4j Modbus Bridge";
	public static final String version     = "1.30";
	public static final String helpURL     = "https://wiki.commander4j.com/index.php?title=ModbusBridge";

	/** Conventional on-disk locations, relative to the working directory. */
	public static final String iconPath   = "." + File.separator + "images" + File.separator + "appIcons" + File.separator;
	public static final File   configFile = new File("xml" + File.separator + "config" + File.separator + "config.xml");

	private CommonBridge()
	{
	}

	public static String buildTitle(String suffix)
	{
		String base = programName + " " + version;
		if (suffix == null || suffix.isEmpty())
		{
			return base;
		}
		return base + " [" + suffix + "]";
	}
}
