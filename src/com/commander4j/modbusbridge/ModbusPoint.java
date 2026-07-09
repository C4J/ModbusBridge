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
 *
 * <p>When {@code initialise} is set the bridge writes {@code initialValue} to the point
 * once, on its first successful connect after startup, but only if the point is not already
 * at that value (see {@code PollThread}). Only writable points may be initialised. The value
 * is stored normalised: 0/1 for coils, the raw 16-bit value for holding registers.
 *
 * <p>When {@code simulate} is set the point is detached from the wire entirely: the poll
 * thread never reads it, writes and pulses update only the in-memory cache, and it is never
 * marked stale — REST callers cannot tell it from a live point. {@code initialValue} then
 * seeds the simulated value at startup (default 0). {@code simulate} and {@code initialise}
 * are mutually exclusive (an initialise write needs the wire the point no longer has).
 *
 * <p>Writable points also carry a per-point pulse hold policy — {@code minHoldMs} /
 * {@code maxHoldMs} bound what a pulse request may ask for, and {@code defaultHoldMs} is
 * what the web UI pre-fills. {@code ConfigStore} resolves the config attributes (and their
 * defaults) into always-valid values here; read-only points carry zeroes and reject the
 * attributes at load.
 *
 * <p>{@code pulseAllowed} (config attribute {@code pulse}, default {@code true}) marks a
 * writable point as must-never-be-pulsed — e.g. a setpoint register a momentary cycle would
 * corrupt. The pulse endpoint then rejects it with 400, the points payload omits its hold
 * policy fields, and the web UI shows no Pulse control. Plain writes are unaffected.
 */
public record ModbusPoint(String name, RegisterKind kind, int address, boolean simulate, boolean initialise, int initialValue, boolean pulseAllowed, int minHoldMs, int maxHoldMs, int defaultHoldMs)
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
		boolean writable = kind == RegisterKind.COILS || kind == RegisterKind.HOLDING_REGISTERS;
		if (simulate && initialise)
		{
			throw new IllegalArgumentException("point '" + name + "' cannot combine simulate=\"true\" with initialise=\"true\"");
		}
		if (initialise && !writable)
		{
			throw new IllegalArgumentException("point '" + name + "' cannot be initialised: " + kind.name() + " is read-only");
		}
		if ((initialise || simulate) && !kind.bit && (initialValue < 0 || initialValue > 0xFFFF))
		{
			throw new IllegalArgumentException("point '" + name + "' " + (simulate ? "seed" : "initial") + " value out of range (0..65535): " + initialValue);
		}
		if (simulate && kind.bit && initialValue != 0 && initialValue != 1)
		{
			throw new IllegalArgumentException("point '" + name + "' seed value must be 0 or 1: " + initialValue);
		}
		if (writable)
		{
			if (minHoldMs < 1)
			{
				throw new IllegalArgumentException("point '" + name + "' minHoldMs must be at least 1: " + minHoldMs);
			}
			if (maxHoldMs < minHoldMs)
			{
				throw new IllegalArgumentException("point '" + name + "' maxHoldMs (" + maxHoldMs + ") must not be less than minHoldMs (" + minHoldMs + ")");
			}
			if (defaultHoldMs < minHoldMs || defaultHoldMs > maxHoldMs)
			{
				throw new IllegalArgumentException("point '" + name + "' defaultHoldMs out of range (" + minHoldMs + ".." + maxHoldMs + "): " + defaultHoldMs);
			}
		}
	}

	/** {@code true} if a client may write this point (coils and holding registers only). */
	public boolean writable()
	{
		return kind == RegisterKind.COILS || kind == RegisterKind.HOLDING_REGISTERS;
	}
}
