package com.commander4j.modbusbridge.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;

/**
 * A Server-Sent Events fan-out for one stream (one instance per logical stream: the live
 * points table uses one, the log tail another).
 *
 * <p><b>Threading model.</b> Each open connection owns a bounded queue and is drained by
 * the very handler thread that served {@code /events} — that thread blocks on
 * {@link java.util.concurrent.BlockingQueue#poll(long, TimeUnit)}, writes each frame, and
 * flushes. Producers (the poll thread, REST writes, the log tailer) only ever
 * {@code offer} — never block on a slow or dead client. This is why the embedded server
 * must use a cached, daemon-threaded pool: every live SSE connection parks one handler
 * thread for its lifetime.
 *
 * <p><b>Full-snapshot-on-change.</b> {@link #broadcastCurrent()} pushes the whole current
 * state (from the {@code snapshot} supplier), not a delta. Because a producer's mutation
 * happens-before its own broadcast, the latest state is always sent even if broadcasts
 * race, and duplicate full sends are harmless. On a slow consumer the queue coalesces
 * (drop the backlog, keep only the newest frame) — correct precisely because each frame is
 * self-contained.
 *
 * <p><b>Registration race.</b> Seeding a new connection with the current snapshot is done
 * under the same monitor {@link #broadcast} holds while iterating, so a new connection
 * either gets the seed before a concurrent change (and then the follow-up broadcast) or the
 * seed already reflecting it — never a missed-only-change that leaves it permanently stale.
 */
final class SseHub
{
	private static final Logger log = LoggerFactory.getLogger(SseHub.class);

	/** Idle gap after which a heartbeat comment is written (also detects dead sockets). */
	private static final long HEARTBEAT_MS = 15000;
	/** Per-connection frame backlog before coalescing kicks in. */
	private static final int QUEUE_CAP = 64;

	private final String name;
	private final Supplier<String> snapshot; // current full payload for seeding/broadcastCurrent; null for log
	private final Object lock = new Object();
	private final List<Conn> conns = new ArrayList<>(); // guarded by lock
	private boolean closed = false; // guarded by lock

	/**
	 * @param name     short label for log/thread context
	 * @param snapshot supplies the current full payload (used to seed new connections and by
	 *                 {@link #broadcastCurrent()}); {@code null} for streams that only push
	 *                 explicit data (the log tail) and have no seedable snapshot
	 */
	SseHub(String name, Supplier<String> snapshot)
	{
		this.name = name;
		this.snapshot = snapshot;
	}

	/**
	 * Takes over the calling handler thread for the life of the connection: sends the SSE
	 * headers, seeds the current snapshot, then drains this connection's queue (writing a
	 * heartbeat comment on idle) until the client disconnects or the hub stops. Always
	 * closes the exchange and deregisters before returning.
	 */
	void register(HttpExchange ex) throws IOException
	{
		ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-cache");
		ex.getResponseHeaders().set("Connection", "keep-alive");
		ex.getResponseHeaders().set("X-Accel-Buffering", "no"); // disable proxy buffering (nginx)
		ex.sendResponseHeaders(200, 0); // 0 => chunked, response stays open

		Conn conn = new Conn(ex, ex.getResponseBody());
		conn.thread = Thread.currentThread();
		synchronized (lock)
		{
			if (closed)
			{
				conn.closeQuietly();
				return;
			}
			conns.add(conn);
			if (snapshot != null)
			{
				String s = snapshot.get();
				if (s != null)
				{
					conn.queue.offer(frame(s));
				}
			}
		}
		log.debug("SSE[{}] client connected ({} open)", name, conns.size());

		try
		{
			conn.pump();
		}
		finally
		{
			synchronized (lock)
			{
				conns.remove(conn);
			}
			conn.closeQuietly();
			log.debug("SSE[{}] client disconnected ({} open)", name, conns.size());
		}
	}

	/** Pushes an explicit data payload (one SSE event) to every open connection. */
	void broadcast(String data)
	{
		String frame = frame(data);
		synchronized (lock)
		{
			for (Conn c : conns)
			{
				c.enqueue(frame);
			}
		}
	}

	/** Pushes the current full snapshot to every open connection. No-op if no supplier. */
	void broadcastCurrent()
	{
		if (snapshot == null)
		{
			return;
		}
		String data = snapshot.get();
		if (data != null)
		{
			broadcast(data);
		}
	}

	/** Unblocks and closes every connection so blocked handler threads exit at shutdown. */
	void stop()
	{
		List<Conn> toClose;
		synchronized (lock)
		{
			closed = true;
			toClose = new ArrayList<>(conns);
			conns.clear();
		}
		for (Conn c : toClose)
		{
			c.open = false;
			Thread t = c.thread;
			if (t != null)
			{
				t.interrupt();
			}
			c.closeQuietly();
		}
	}

	private static String frame(String data)
	{
		// data is single-line: JSON escapes newlines; log lines are split before broadcast.
		return "data: " + data + "\n\n";
	}

	/** One open SSE connection. Drained by the handler thread that created it. */
	private final class Conn
	{
		private final HttpExchange ex;
		private final OutputStream os;
		private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAP);
		private volatile boolean open = true;
		private volatile Thread thread;

		Conn(HttpExchange ex, OutputStream os)
		{
			this.ex = ex;
			this.os = os;
		}

		/** Offer a frame; if the client is too slow, drop its backlog and keep the newest. */
		void enqueue(String frame)
		{
			if (!queue.offer(frame))
			{
				queue.clear();
				queue.offer(frame);
			}
		}

		/** Blocking write loop — runs on the handler thread until disconnect or stop. */
		void pump()
		{
			try
			{
				while (open && !closed)
				{
					String frame = queue.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
					if (frame == null)
					{
						frame = ":\n\n"; // heartbeat comment; surfaces a dead socket as IOException
					}
					os.write(frame.getBytes(StandardCharsets.UTF_8));
					os.flush();
				}
			}
			catch (IOException e)
			{
				// client went away; fall through to cleanup in register()'s finally
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt(); // hub stopping
			}
		}

		void closeQuietly()
		{
			open = false;
			try
			{
				os.close();
			}
			catch (IOException ignored)
			{
				// already gone
			}
			ex.close();
		}
	}
}
