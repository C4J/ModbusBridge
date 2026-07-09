package com.commander4j.modbusbridge;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.commander4j.modbus.ClientController;
import com.commander4j.modbus.RegisterKind;

/**
 * Drives momentary "pulse" outputs: set a writable point (a PLC relay coil, typically) to an
 * active value, hold it for a bounded time, then <em>guarantee</em> it returns to a rest value —
 * with the reset owned by the bridge, not the caller's web session. A dropped session can never
 * leave a relay energised.
 *
 * <h2>Safety model</h2>
 * <ol>
 *   <li><b>Synchronous set, asynchronous guaranteed reset.</b> The SET runs on the caller's
 *       request thread, so the HTTP response is a real confirmation the relay fired
 *       ({@link Result#ACCEPTED} → 200) or that nothing was energised
 *       ({@link Result#WRITE_FAILED} → 503, no reset owed). The decision keys off whether the
 *       write <em>call returned</em>, never a read-back: a write that lands must always schedule
 *       its reset. The reset then runs on a background worker, fully decoupled from the session.</li>
 *   <li><b>Reset retries while the link is down.</b> This is a deliberate exception to the "REST
 *       writes never reconnect → 503" rule: once a relay is energised the bridge owes a reset, so
 *       the reset retries (riding the poll thread's reconnect) until it lands or
 *       {@link #RESET_RETRY_TIMEOUT_MS} elapses, when it raises a loud {@code ALARM}. Safe and
 *       lock-free because {@code ClientController.writeBit} does its Netty I/O outside the
 *       controller's monitor and throws when disconnected.</li>
 *   <li><b>Shutdown flushes pending resets before disconnect.</b> {@link #flush()} skips the
 *       remaining hold of every energised pulse and waits for its reset to land, and must be
 *       called (by {@code Main}'s shutdown hook) <em>before</em> the Modbus link is closed — else
 *       a pulse caught mid-hold at SIGTERM would leave the relay energised across a restart.</li>
 *   <li><b>Honest limit:</b> reset is guaranteed across session drop, link drop and graceful
 *       shutdown — <em>not</em> across {@code kill -9} / power loss. That needs a PLC-side
 *       hardware watchdog (auto-reset if a coil is not refreshed).</li>
 * </ol>
 *
 * <h2>Concurrency</h2>
 * Each point owns a single-thread executor with a bounded queue, so pulses on the same point
 * run one fully-completed set→hold→reset cycle at a time (queue-after-current); the relay
 * therefore returns to rest <em>between</em> queued pulses. A caller that outruns the queue is
 * rejected with {@link Result#QUEUE_FULL} → 409 rather than building an unbounded backlog.
 * Pulses on <em>different</em> points are independent. A plain {@code PUT} on the same point mid-
 * hold is not coordinated with the pulse, but is benign: the reset always runs last, so the
 * rest-state invariant holds regardless.
 */
public final class PulseService
{
	private static final Logger log = LoggerFactory.getLogger(PulseService.class);

	/** Max pulses that may wait behind the running one per point before 409s start. */
	static final int MAX_QUEUE_PER_POINT = 8;

	/**
	 * How long a reset keeps retrying a down link before giving up with an {@code ALARM}. This
	 * also bounds how long {@link #flush()} (hence graceful shutdown) can block when the link is
	 * down at exit; kept modest so shutdown stays responsive while still covering a couple of the
	 * poll thread's reconnect-backoff cycles.
	 */
	static final long RESET_RETRY_TIMEOUT_MS = 10000;

	/** Pause between reset attempts while the link is down. */
	static final long RESET_RETRY_INTERVAL_MS = 500;

	private final ClientController controller;
	private final PointService service;

	/** One per writable point, keyed by point name. */
	private final ConcurrentHashMap<String, ThreadPoolExecutor> executors = new ConcurrentHashMap<>();

	/** Energised pulses still owing a reset — the flush registry. */
	private final Set<PulseJob> pendingResets = ConcurrentHashMap.newKeySet();

	private volatile boolean draining = false;

	public PulseService(ClientController controller, PointService service)
	{
		this.controller = controller;
		this.service = service;
		for (ModbusPoint p : service.points())
		{
			if (p.writable() && p.pulseAllowed())
			{
				executors.put(p.name(), newExecutor(p.name()));
			}
		}
	}

	/** Outcome of a {@link #pulse} call, mapped to an HTTP status by the web layer. */
	public enum Result
	{
		/** Relay energised; reset scheduled → 200. */
		ACCEPTED,
		/** The set write failed; nothing energised, no reset owed → 503. */
		WRITE_FAILED,
		/** The point's pending queue is full → 409. */
		QUEUE_FULL
	}

	/**
	 * Energises {@code point} to {@code value}, holds it for {@code holdMs}, then resets it to
	 * {@code restValue}. Blocks only until the SET completes (or is rejected); the hold and reset
	 * proceed on a background worker. Callers must have already validated that {@code point} is
	 * writable and that {@code holdMs}/{@code value} are in range (the hold bounds are per-point:
	 * {@code ModbusPoint.minHoldMs()}/{@code maxHoldMs()}).
	 */
	public Result pulse(ModbusPoint point, int value, long holdMs, int restValue)
	{
		ThreadPoolExecutor exec = executors.get(point.name());
		if (exec == null)
		{
			// Non-writable and pulse="false" points have no executor; the web layer
			// rejects both with 400 before ever calling here.
			return Result.WRITE_FAILED;
		}
		PulseJob job = new PulseJob(point, value, holdMs, restValue);
		try
		{
			exec.execute(job);
		}
		catch (RejectedExecutionException e)
		{
			return Result.QUEUE_FULL;
		}
		try
		{
			job.setDone.await();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		return job.setSucceeded ? Result.ACCEPTED : Result.WRITE_FAILED;
	}

	/**
	 * Shutdown hook: stop accepting new pulses, force every energised pulse to reset now (skip
	 * the remaining hold), and wait — bounded by {@link #RESET_RETRY_TIMEOUT_MS} — for the resets
	 * to land. Must run before the Modbus link is closed.
	 */
	public void flush()
	{
		draining = true;
		for (ThreadPoolExecutor exec : executors.values())
		{
			// shutdown(), never shutdownNow(): queued jobs still run (they self-abort while
			// draining) and a running reset must complete — see the interrupt note in
			// resetWithRetry(). Do not switch this to shutdownNow()/interrupt.
			exec.shutdown();
		}
		for (PulseJob job : pendingResets)
		{
			job.holdLatch.countDown(); // reset immediately instead of finishing the hold
		}
		long deadline = System.currentTimeMillis() + RESET_RETRY_TIMEOUT_MS + 2000;
		for (ThreadPoolExecutor exec : executors.values())
		{
			long remaining = deadline - System.currentTimeMillis();
			try
			{
				if (remaining > 0 && !exec.awaitTermination(remaining, TimeUnit.MILLISECONDS))
				{
					log.warn("Pulse resets did not all complete before shutdown deadline");
				}
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
		}
		log.info("Pulse service flushed");
	}

	private void write(ModbusPoint p, int value) throws Exception
	{
		if (p.simulate())
		{
			// Simulated points never touch the wire: the write trivially "lands", so the
			// whole set→hold→reset cycle (and its cache updates / SSE pushes) behaves
			// exactly like a live point, minus the Modbus I/O.
			return;
		}
		if (p.kind() == RegisterKind.COILS)
		{
			controller.writeBit(RegisterKind.COILS, p.address(), value != 0);
		}
		else
		{
			controller.writeRegister(RegisterKind.HOLDING_REGISTERS, p.address(), value);
		}
	}

	/** The value the cache should show after writing {@code value} (coils normalise to 0/1). */
	private static int applied(ModbusPoint p, int value)
	{
		return p.kind() == RegisterKind.COILS ? (value != 0 ? 1 : 0) : value;
	}

	private ThreadPoolExecutor newExecutor(String pointName)
	{
		AtomicInteger n = new AtomicInteger(1);
		ThreadFactory tf = r ->
		{
			Thread t = new Thread(r, "modbus-bridge-pulse-" + pointName + "-" + n.getAndIncrement());
			t.setDaemon(true);
			return t;
		};
		// core = max = 1 so pulses on this point serialise; a bounded queue + AbortPolicy gives
		// queue-after-current with a hard cap (over-cap → RejectedExecutionException → 409). Core
		// thread times out so idle points hold no thread.
		ThreadPoolExecutor exec = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_QUEUE_PER_POINT), tf, new ThreadPoolExecutor.AbortPolicy());
		exec.allowCoreThreadTimeOut(true);
		return exec;
	}

	/** One set→hold→reset cycle. Runs on its point's single worker thread. */
	private final class PulseJob implements Runnable
	{
		private final ModbusPoint point;
		private final int value;
		private final long holdMs;
		private final int restValue;

		/** Tripped once the SET has succeeded or failed, releasing the waiting request thread. */
		private final CountDownLatch setDone = new CountDownLatch(1);
		/** Counted down to cut the hold short (normal timeout, or {@link #flush()}). */
		private final CountDownLatch holdLatch = new CountDownLatch(1);
		private volatile boolean setSucceeded = false;

		PulseJob(ModbusPoint point, int value, long holdMs, int restValue)
		{
			this.point = point;
			this.value = value;
			this.holdMs = holdMs;
			this.restValue = restValue;
		}

		@Override
		public void run()
		{
			boolean resetOwed = false;
			try
			{
				if (draining)
				{
					// Queued behind a pulse when shutdown began; do not energise on the way out.
					return;
				}
				try
				{
					write(point, value);
					service.update(point.name(), applied(point, value));
					pendingResets.add(this); // register the reset BEFORE the request returns 200
					resetOwed = true;
					setSucceeded = true;
					log.info("Pulse {} set to {} (hold {} ms, reset to {})", point.name(), value, holdMs, restValue);
				}
				catch (Exception e)
				{
					log.warn("Pulse set of '{}' = {} failed: {}", point.name(), value, e.getMessage());
					return; // setSucceeded stays false → caller gets 503
				}
			}
			finally
			{
				setDone.countDown();
			}

			if (!resetOwed)
			{
				return;
			}

			try
			{
				if (!draining)
				{
					holdLatch.await(holdMs, TimeUnit.MILLISECONDS);
				}
				resetWithRetry();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				resetWithRetry(); // still owe the reset even if the hold was interrupted
			}
			finally
			{
				pendingResets.remove(this);
			}
		}

		private void resetWithRetry()
		{
			long deadline = System.currentTimeMillis() + RESET_RETRY_TIMEOUT_MS;
			while (true)
			{
				try
				{
					write(point, restValue);
					service.update(point.name(), applied(point, restValue));
					log.info("Pulse {} reset to {}", point.name(), restValue);
					return;
				}
				catch (Exception e)
				{
					if (System.currentTimeMillis() >= deadline)
					{
						log.error("ALARM: pulse reset of '{}' to {} FAILED after {} ms — point may remain at {}: {}", point.name(), restValue, RESET_RETRY_TIMEOUT_MS, value, e.getMessage());
						return;
					}
					log.warn("Pulse reset of '{}' to {} pending (link down?), retrying: {}", point.name(), restValue, e.getMessage());
					try
					{
						Thread.sleep(RESET_RETRY_INTERVAL_MS);
					}
					catch (InterruptedException ie)
					{
						// This retry loop assumes it is never interrupted: {@link #flush()} uses
						// countDown + shutdown() (not interrupt/shutdownNow) precisely so a reset
						// runs to completion. If flush is ever changed to interrupt this thread,
						// re-setting the flag here would make the sleep throw every iteration and
						// busy-spin to the deadline — so clear it and keep pacing the retries.
						Thread.interrupted();
					}
				}
			}
		}
	}
}
