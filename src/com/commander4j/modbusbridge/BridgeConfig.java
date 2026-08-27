package com.commander4j.modbusbridge;

import java.util.List;

/**
 * Immutable view of the bridge configuration loaded from {@code xml/config/config.xml}.
 * Extends the client's three-field schema ({@code ip}/{@code port}/{@code id}) with a
 * poll interval, an embedded web-server bind address, and the list of named points.
 *
 * @param host           remote Modbus server host
 * @param port           remote Modbus server TCP port
 * @param unitId         Modbus unit id to address (0..247)
 * @param pollIntervalMs how often the poll thread refreshes every point
 * @param modbusEnabled  {@code false} (via {@code <modbus enabled="false">}) stops the bridge
 *                       connecting to the Modbus server at all — for a device that is known to
 *                       be out of service. Real points then stay stale and writes return 503;
 *                       simulated points are unaffected. Absent → {@code true}
 * @param webHost        bind address for the embedded HTTP server (added in step 3)
 * @param webPort        listen port for the embedded HTTP server (added in step 3)
 * @param maxHoldMs      default upper bound on a pulse's hold time, applied to any point
 *                       that doesn't set its own {@code maxHoldMs} attribute, so a typo
 *                       can't energise a relay for hours; optional in the XML (defaults
 *                       applied in {@link ConfigStore}). The enforced bounds live on each
 *                       {@link ModbusPoint}.
 * @param points         the named points to track, in declaration order
 * @param restIds        application-facing names from the optional {@code <restapi>} section,
 *                       each resolving to one of the {@code points} (several ids may share a
 *                       point — see {@link RestApiId}); empty when the section is absent
 */
public record BridgeConfig(String host, int port, int unitId, int pollIntervalMs, boolean modbusEnabled, String webHost, int webPort, int maxHoldMs, List<ModbusPoint> points, List<RestApiId> restIds)
{
	public BridgeConfig
	{
		points = List.copyOf(points);
		restIds = List.copyOf(restIds);
	}
}
