package com.commander4j.modbusbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory cache of the latest value of every configured {@link ModbusPoint}, plus the
 * bridge's view of the connection. The {@link PollThread} writes here each tick; REST
 * handlers and the SSE pump (added later) read from here.
 *
 * <p>A {@link Sample} carries the last value, whether it has ever been read
 * ({@code valid}), and whether it is {@code stale} (the connection dropped since it was
 * last read). On disconnect the poll thread calls {@link #markAllStale()} so consumers
 * keep showing the last-known value flagged as stale rather than blanking out.
 *
 * <p>The class is thread-safe: the sample map is mutated only under {@code this}, reads
 * take a snapshot copy, and {@code connected} is an {@link AtomicBoolean}.
 */
public final class PointService
{
	/** Immutable reading for one point. {@code value} holds 0/1 for bit kinds. */
	public record Sample(ModbusPoint point, int value, boolean valid, boolean stale)
	{
	}

	private final Map<String, Sample> samples = new LinkedHashMap<>();
	private final List<ModbusPoint> points;
	private final AtomicBoolean connected = new AtomicBoolean(false);
	private volatile long lastPollMillis = 0;
	private volatile Runnable changeListener = () -> {};

	public PointService(List<ModbusPoint> points)
	{
		this.points = List.copyOf(points);
		for (ModbusPoint p : this.points)
		{
			// Simulated points are born valid at their seed value and never go stale;
			// real points start invalid/stale until the first successful read.
			samples.put(p.name(), p.simulate() ? new Sample(p, p.initialValue(), true, false) : new Sample(p, 0, false, true));
		}
	}

	public List<ModbusPoint> points()
	{
		return points;
	}

	public boolean isConnected()
	{
		return connected.get();
	}

	public void setConnected(boolean value)
	{
		if (connected.getAndSet(value) != value)
		{
			changeListener.run();
		}
	}

	/**
	 * Registers a callback fired whenever the observable state changes (a value, a
	 * valid/stale flip, or the connection flag). The SSE layer wires this to push the
	 * current snapshot. The callback runs <em>outside</em> this object's lock and on the
	 * mutating thread (poll or REST), so it must be non-blocking — the SSE hub only does a
	 * non-blocking enqueue.
	 */
	public void setChangeListener(Runnable listener)
	{
		this.changeListener = listener == null ? () -> {} : listener;
	}

	/** Epoch millis of the last fully successful poll pass, or 0 if none yet. */
	public long lastPollMillis()
	{
		return lastPollMillis;
	}

	public void recordPoll(long epochMillis)
	{
		lastPollMillis = epochMillis;
	}

	/**
	 * Records a fresh reading. Returns {@code true} if this changed the observable state
	 * (value differed, or the point was previously invalid/stale) — the signal the SSE
	 * pump will later use to decide whether to push.
	 */
	public boolean update(String name, int value)
	{
		boolean changed;
		synchronized (this)
		{
			Sample prev = samples.get(name);
			if (prev == null)
			{
				throw new IllegalArgumentException("unknown point: " + name);
			}
			changed = !prev.valid() || prev.stale() || prev.value() != value;
			samples.put(name, new Sample(prev.point(), value, true, false));
		}
		if (changed)
		{
			changeListener.run();
		}
		return changed;
	}

	/**
	 * Flags every sample stale (keeping its last value) — used when the link drops.
	 * Simulated points are exempt: they are not backed by the wire, so a dead link
	 * cannot invalidate them.
	 */
	public void markAllStale()
	{
		boolean any = false;
		synchronized (this)
		{
			for (Map.Entry<String, Sample> e : samples.entrySet())
			{
				Sample s = e.getValue();
				if (!s.stale() && !s.point().simulate())
				{
					e.setValue(new Sample(s.point(), s.value(), s.valid(), true));
					any = true;
				}
			}
		}
		if (any)
		{
			changeListener.run();
		}
	}

	public synchronized Sample sample(String name)
	{
		return samples.get(name);
	}

	/** A point-in-time copy of every sample, in configuration order. */
	public synchronized List<Sample> snapshot()
	{
		return Collections.unmodifiableList(new ArrayList<>(samples.values()));
	}
}
