package com.commander4j.modbusbridge;

import com.commander4j.modbus.RegisterKind;

/**
 * A single named address the bridge tracks: a human-friendly {@code name}, the Modbus
 * data table it lives in ({@code kind}), and its zero-based protocol {@code address}.
 *
 * <p>REST clients and the web UI refer to points by {@code name}; the bridge never
 * exposes raw addresses to callers. {@link #writable()} mirrors the Modbus rule that
 * only coils and holding registers accept a client write — the same constraint
 * {@code ClientController.writeBit}/{@code writeRegister} enforce.
 */
public record ModbusPoint(String name, RegisterKind kind, int address)
{
	public ModbusPoint
	{
		if (name == null || name.isBlank())
		{
			throw new IllegalArgumentException("point name must not be blank");
		}
		if (kind == null)
		{
			throw new IllegalArgumentException("point '" + name + "' has no kind");
		}
		if (address < 0 || address > 0xFFFF)
		{
			throw new IllegalArgumentException("point '" + name + "' address out of range (0..65535): " + address);
		}
	}

	/** {@code true} if a client may write this point (coils and holding registers only). */
	public boolean writable()
	{
		return kind == RegisterKind.COILS || kind == RegisterKind.HOLDING_REGISTERS;
	}
}
