package com.commander4j.modbusbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.commander4j.modbus.ClientController;
import com.commander4j.modbus.RegisterKind;

/**
 * Background daemon that keeps {@link PointService} in step with the remote server.
 *
 * <p>Each iteration either (a) reconnects, if the link is down, or (b) reads every
 * configured point once and writes the values into the service. A read failure tears the
 * connection down, flags all samples stale, and drops back to the reconnect path — the
 * "offline → recover" behaviour the interactive client app never had (it stopped and
 * waited for the operator). Reconnect uses exponential backoff between
 * {@link #MIN_BACKOFF_MS} and {@link #MAX_BACKOFF_MS}, reset on a successful connect.
 *
 * <p>All sleeps are interruptible so {@link #shutdown()} returns promptly: it clears the
 * running flag, interrupts the thread, and joins with a timeout.
 */
public final class PollThread extends Thread
{
	private static final Logger log = LoggerFactory.getLogger(PollThread.class);

	static final long MIN_BACKOFF_MS = 1000;
	static final long MAX_BACKOFF_MS = 30000;

	private final ClientController controller;
	private final PointService service;
	private final BridgeConfig cfg;

	private volatile boolean running = true;
	private long backoffMs = MIN_BACKOFF_MS;
	private boolean initialised = false;

	public PollThread(ClientController controller, PointService service, BridgeConfig cfg)
	{
		super("modbus-bridge-poll");
		setDaemon(true);
		this.controller = controller;
		this.service = service;
		this.cfg = cfg;
	}

	@Override
	public void run()
	{
		controller.setUnitId(cfg.unitId());
		long simulated = cfg.points().stream().filter(ModbusPoint::simulate).count();
		log.info("Poll thread started: target {}:{} unit {}, {} point(s) ({} simulated), interval {} ms", cfg.host(), cfg.port(), cfg.unitId(), cfg.points().size(), simulated, cfg.pollIntervalMs());

		while (running)
		{
			if (!controller.isConnected())
			{
				if (!tryConnect())
				{
					if (!sleepMs(backoffMs))
					{
						break;
					}
					backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
					continue;
				}
				backoffMs = MIN_BACKOFF_MS;

				// Startup initialisation runs once, on the first successful connect. If it
				// fails mid-pass the link is torn down and retried; it stays un-done so the
				// next connect attempts it again. Once done it is not repeated on reconnect,
				// so runtime REST/operator changes survive an outage.
				if (!initialised)
				{
					if (!initialisePoints())
					{
						continue; // torn down; loop back to reconnect and retry init
					}
					initialised = true;
				}
			}

			if (!pollOnce())
			{
				continue; // connection torn down; loop back to reconnect
			}
			service.recordPoll(System.currentTimeMillis());

			if (!sleepMs(cfg.pollIntervalMs()))
			{
				break;
			}
		}

		// The loop exits via an interrupt at shutdown; clear that flag so the final
		// disconnect's blocking channel close completes cleanly instead of aborting.
		Thread.interrupted();
		safeDisconnect();
		log.info("Poll thread stopped");
	}

	/** Attempts a single connect. Returns true on success. */
	private boolean tryConnect()
	{
		try
		{
			controller.connect(cfg.host(), cfg.port());
			service.setConnected(true);
			log.info("Connected to {}:{}", cfg.host(), cfg.port());
			return true;
		}
		catch (Exception e)
		{
			log.warn("Connect to {}:{} failed: {} (retry in {} ms)", cfg.host(), cfg.port(), e.getMessage(), backoffMs);
			return false;
		}
	}

	/**
	 * Reads every point once. Returns true if the whole pass succeeded; false if a read
	 * failed, in which case the connection has been torn down and samples flagged stale.
	 */
	private boolean pollOnce()
	{
		for (ModbusPoint p : cfg.points())
		{
			if (p.simulate())
			{
				continue; // simulated points live in the cache only — never on the wire
			}
			try
			{
				int value = readPoint(p);
				if (service.update(p.name(), value))
				{
					log.debug("{} ({} @{}) = {}", p.name(), p.kind().name(), p.address(), value);
				}
			}
			catch (Exception e)
			{
				log.warn("Read of '{}' failed: {} — dropping connection", p.name(), e.getMessage());
				service.markAllStale();
				service.setConnected(false);
				safeDisconnect();
				return false;
			}
		}
		return true;
	}

	/**
	 * One-time startup pass: for every point flagged {@code initialise}, read the server's
	 * current value and write the configured value only if they differ (so an already-correct
	 * point is left untouched). Returns true if the whole pass succeeded; false if a read or
	 * write failed, in which case the connection has been torn down (mirroring {@link #pollOnce}).
	 */
	private boolean initialisePoints()
	{
		for (ModbusPoint p : cfg.points())
		{
			if (!p.initialise())
			{
				continue;
			}
			try
			{
				int current = readPoint(p);
				int desired = p.initialValue();
				if (current == desired)
				{
					log.info("Initialise: {} already at {} — no write", p.name(), desired);
				}
				else
				{
					writeValue(p, desired);
					log.info("Initialise: {} set to {} (was {})", p.name(), desired, current);
				}
				service.update(p.name(), desired);
			}
			catch (Exception e)
			{
				log.warn("Initialise of '{}' failed: {} — dropping connection", p.name(), e.getMessage());
				service.markAllStale();
				service.setConnected(false);
				safeDisconnect();
				return false;
			}
		}
		return true;
	}

	private void writeValue(ModbusPoint p, int value) throws Exception
	{
		if (p.kind() == RegisterKind.COILS)
		{
			controller.writeBit(RegisterKind.COILS, p.address(), value != 0);
		}
		else
		{
			controller.writeRegister(RegisterKind.HOLDING_REGISTERS, p.address(), value);
		}
	}

	private int readPoint(ModbusPoint p) throws Exception
	{
		if (p.kind().bit)
		{
			boolean[] bits = controller.readBits(p.kind(), p.address(), 1);
			return bits[0] ? 1 : 0;
		}
		int[] regs = controller.readRegisters(p.kind(), p.address(), 1);
		return regs[0] & 0xFFFF;
	}

	private void safeDisconnect()
	{
		try
		{
			controller.disconnect();
		}
		catch (Exception e)
		{
			log.debug("Disconnect threw (ignored): {}", e.getMessage());
		}
		service.setConnected(false);
	}

	/** Sleeps, returning false if interrupted (signal to stop the loop). */
	private boolean sleepMs(long ms)
	{
		try
		{
			Thread.sleep(ms);
			return running;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Stops the thread: clears the flag, interrupts any sleep, and joins with a timeout. */
	public void shutdown()
	{
		running = false;
		interrupt();
		try
		{
			join(MIN_BACKOFF_MS + cfg.pollIntervalMs());
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
}
