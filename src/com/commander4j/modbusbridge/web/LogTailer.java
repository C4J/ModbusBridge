package com.commander4j.modbusbridge.web;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follows the rolling log file and pushes each newly-appended line to an {@link SseHub}, so
 * {@code log.html} updates live. Backlog (the lines already present when a page loads) is
 * served separately by {@link #tail(int)} via {@code /api/log/tail}; this tailer starts at
 * end-of-file and streams only what is written afterwards.
 *
 * <p><b>Roll handling.</b> log4j2 rolls {@code modbusBridge.log} at its size cap by moving
 * it aside and recreating an empty file. The tailer watches for the file shrinking below
 * the tracked position and resets to the start of the new file, so following survives a
 * roll instead of going silent.
 *
 * <p>A brief gap is possible: lines written between a page's {@code /api/log/tail} fetch and
 * its {@code EventSource} connecting are neither in the backlog nor delivered to that page.
 * Acceptable for a log viewer.
 */
public final class LogTailer
{
	private static final Logger log = LoggerFactory.getLogger(LogTailer.class);
	private static final long POLL_MS = 1000;
	private static final int MAX_CHUNK = 1 << 20; // 1 MB read cap per pass

	private final Path file;
	private final SseHub hub;
	private final Thread thread;

	private volatile boolean running = true;
	private long position = 0;

	public LogTailer(Path file, SseHub hub)
	{
		this.file = file;
		this.hub = hub;
		this.thread = new Thread(this::run, "modbus-bridge-logtail");
		this.thread.setDaemon(true);
	}

	public void start()
	{
		try
		{
			position = Files.exists(file) ? Files.size(file) : 0; // stream only new lines
		}
		catch (IOException e)
		{
			position = 0;
		}
		thread.start();
		log.debug("Log tailer started on {} (from offset {})", file, position);
	}

	public void stop()
	{
		running = false;
		thread.interrupt();
		try
		{
			thread.join(2000);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		log.debug("Log tailer stopped");
	}

	/** Returns the last {@code n} lines of the current log file (newest backlog first call). */
	public List<String> tail(int n) throws IOException
	{
		if (!Files.exists(file))
		{
			return List.of();
		}
		List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
		int from = Math.max(0, all.size() - n);
		return new ArrayList<>(all.subList(from, all.size()));
	}

	private void run()
	{
		StringBuilder partial = new StringBuilder();
		while (running)
		{
			try
			{
				if (Files.exists(file))
				{
					long len = Files.size(file);
					if (len < position)
					{
						// file rolled / truncated: restart from the top of the new file
						position = 0;
						partial.setLength(0);
					}
					if (len > position)
					{
						readNewLines(partial);
					}
				}
			}
			catch (IOException e)
			{
				log.debug("Log tail read failed (retrying): {}", e.getMessage());
			}
			try
			{
				Thread.sleep(POLL_MS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	private void readNewLines(StringBuilder partial) throws IOException
	{
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r"))
		{
			raf.seek(position);
			long available = raf.length() - position;
			byte[] buf = new byte[(int) Math.min(available, MAX_CHUNK)];
			int read = raf.read(buf);
			if (read <= 0)
			{
				return;
			}
			// A multi-byte UTF-8 character can be split across two reads; decode only the
			// complete prefix and leave the partial sequence for the next pass, else it
			// decodes to replacement characters.
			int complete = completeUtf8Length(buf, read);
			if (complete == 0)
			{
				return; // nothing decodable yet; wait for the rest of the character
			}
			position += complete;
			partial.append(new String(buf, 0, complete, StandardCharsets.UTF_8));

			int nl;
			while ((nl = partial.indexOf("\n")) >= 0)
			{
				String line = partial.substring(0, nl);
				partial.delete(0, nl + 1);
				if (line.endsWith("\r"))
				{
					line = line.substring(0, line.length() - 1);
				}
				hub.broadcast(line);
			}
		}
	}

	/**
	 * Length of the longest prefix of {@code buf[0..len)} that ends on a UTF-8 character
	 * boundary. If the buffer ends mid-sequence (a lead byte with too few continuation
	 * bytes), those trailing bytes are excluded so the caller can re-read them once the
	 * rest has been written. Malformed input is passed through unchanged — the decoder's
	 * replacement character is no worse than before.
	 */
	private static int completeUtf8Length(byte[] buf, int len)
	{
		int i = len - 1;
		int trailing = 0;
		while (i >= 0 && trailing < 3 && (buf[i] & 0xC0) == 0x80)
		{
			i--;
			trailing++;
		}
		if (i < 0)
		{
			return len; // nothing but continuation bytes: malformed, let the decoder cope
		}
		int lead = buf[i] & 0xFF;
		int need;
		if (lead >= 0xF0)
		{
			need = 4;
		}
		else if (lead >= 0xE0)
		{
			need = 3;
		}
		else if (lead >= 0xC0)
		{
			need = 2;
		}
		else
		{
			return len; // last non-continuation byte is single-byte / malformed: nothing to hold back
		}
		int present = trailing + 1;
		return present < need ? i : len;
	}
}
