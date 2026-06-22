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
 * @param webHost        bind address for the embedded HTTP server (added in step 3)
 * @param webPort        listen port for the embedded HTTP server (added in step 3)
 * @param points         the named points to track, in declaration order
 */
public record BridgeConfig(String host, int port, int unitId, int pollIntervalMs, String webHost, int webPort, List<ModbusPoint> points)
{
	public BridgeConfig
	{
		points = List.copyOf(points);
	}
}
