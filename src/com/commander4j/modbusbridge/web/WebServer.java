package com.commander4j.modbusbridge.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.commander4j.modbus.ClientController;
import com.commander4j.modbus.RegisterKind;
import com.commander4j.modbusbridge.BridgeConfig;
import com.commander4j.modbusbridge.CommonBridge;
import com.commander4j.modbusbridge.Licences;
import com.commander4j.modbusbridge.ModbusPoint;
import com.commander4j.modbusbridge.PointService;
import com.commander4j.modbusbridge.PointService.Sample;
import com.commander4j.modbusbridge.PulseService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Embedded HTTP front end built on the JDK's {@code com.sun.net.httpserver} (zero extra
 * jars). Serves the REST API, static web assets, two SSE streams ({@code /events} for the
 * live points table, {@code /events/log} for the log tail), and {@code /api/log/tail} for
 * the log backlog.
 *
 * <p>The executor is a daemon-threaded cached pool: the default executor pins one handler
 * thread per open connection, which deadlocks once SSE holds a connection open. Daemon
 * threads also keep a non-signal exit from hanging on the pool.
 *
 * <p>Writes go straight to the reused {@link ClientController}, then immediately read the
 * point back from the server so the response (and the cache) reflect the server's actual
 * state rather than an optimistic guess. Any failure of the write <em>or</em> the
 * read-back maps to 503 — the netty I/O happens outside the controller's lock, so the
 * poll thread can drop the link mid-write.
 */
public final class WebServer
{
	private static final Logger log = LoggerFactory.getLogger(WebServer.class);
	private static final Path WEB_ROOT = Path.of("web").toAbsolutePath().normalize();

	/** Default backlog for {@code /api/log/tail} when no {@code ?lines=} is given. */
	private static final int LOG_TAIL_DEFAULT = 200;
	private static final Path LOG_FILE = Path.of("logs", "modbusBridge.log");

	private final BridgeConfig cfg;
	private final PointService service;
	private final ClientController controller;
	private final PulseService pulse;
	private final HttpServer http;

	private final SseHub pointsHub;
	private final SseHub logHub;
	private final LogTailer logTailer;

	public WebServer(BridgeConfig cfg, PointService service, ClientController controller, PulseService pulse) throws IOException
	{
		this.cfg = cfg;
		this.service = service;
		this.controller = controller;
		this.pulse = pulse;

		this.pointsHub = new SseHub("points", this::pointsPayload);
		this.logHub = new SseHub("log", null);
		this.logTailer = new LogTailer(LOG_FILE, logHub);

		// Push the live points table to every open /events connection on any state change.
		service.setChangeListener(pointsHub::broadcastCurrent);

		http = HttpServer.create(new InetSocketAddress(cfg.webHost(), cfg.webPort()), 0);
		http.setExecutor(Executors.newCachedThreadPool(daemonFactory()));
		http.createContext("/api/points", routeSafely(this::handlePoints));
		http.createContext("/api/status", routeSafely(this::handleStatus));
		http.createContext("/api/licence", routeSafely(this::handleLicence));
		http.createContext("/api/log/tail", routeSafely(this::handleLogTail));
		// Longest-prefix match: register /events/log as its own context so it is not
		// swallowed by /events. SSE handlers bypass routeSafely (which would close the
		// exchange) and hold the handler thread for the connection's lifetime.
		http.createContext("/events/log", sseHandler(logHub));
		http.createContext("/events", sseHandler(pointsHub));
		http.createContext("/", routeSafely(this::handleStatic));
	}

	public void start()
	{
		http.start();
		logTailer.start();
		log.info("Web server listening on http://{}:{}", cfg.webHost(), cfg.webPort());
	}

	public void stop()
	{
		// Unblock SSE handler threads and stop the tailer (it reads the log) BEFORE the
		// caller stops log4j2, so teardown lines still reach the file.
		pointsHub.stop();
		logHub.stop();
		logTailer.stop();
		http.stop(0);
		log.info("Web server stopped");
	}

	// --- routing ------------------------------------------------------------------------

	@FunctionalInterface
	private interface Route
	{
		void handle(HttpExchange ex) throws Exception;
	}

	/** Wraps a route so any uncaught exception becomes a 500 instead of a dropped socket. */
	private com.sun.net.httpserver.HttpHandler routeSafely(Route route)
	{
		return ex ->
		{
			try (ex)
			{
				route.handle(ex);
			}
			catch (Exception e)
			{
				log.warn("Handler error for {} {}: {}", ex.getRequestMethod(), ex.getRequestURI(), e.toString());
				trySendError(ex, 500, "internal error");
			}
		};
	}

	/**
	 * Handler for an SSE stream. Deliberately does <em>not</em> use {@link #routeSafely}:
	 * its try-with-resources would close the exchange immediately, ending the stream.
	 * {@link SseHub#register} keeps the connection open and closes the exchange itself.
	 */
	private com.sun.net.httpserver.HttpHandler sseHandler(SseHub hub)
	{
		return ex ->
		{
			if (!ex.getRequestMethod().equals("GET"))
			{
				try (ex)
				{
					sendError(ex, 405, "method not allowed");
				}
				return;
			}
			try
			{
				hub.register(ex);
			}
			catch (Exception e)
			{
				log.debug("SSE register failed: {}", e.toString());
				ex.close();
			}
		};
	}

	// --- /api/points --------------------------------------------------------------------

	private void handlePoints(HttpExchange ex) throws Exception
	{
		String path = ex.getRequestURI().getPath();
		String method = ex.getRequestMethod();

		if (path.equals("/api/points") || path.equals("/api/points/"))
		{
			if (!method.equals("GET"))
			{
				sendError(ex, 405, "method not allowed");
				return;
			}
			sendJson(ex, 200, pointsPayload());
			return;
		}

		String rest = path.substring("/api/points/".length());
		if (rest.endsWith("/pulse"))
		{
			String pulseName = URLDecoder.decode(rest.substring(0, rest.length() - "/pulse".length()), StandardCharsets.UTF_8);
			Sample ps = service.sample(pulseName);
			if (ps == null)
			{
				sendError(ex, 404, "unknown point: " + pulseName);
				return;
			}
			if (!method.equals("POST"))
			{
				sendError(ex, 405, "method not allowed");
				return;
			}
			pulsePoint(ex, ps.point());
			return;
		}

		String name = URLDecoder.decode(rest, StandardCharsets.UTF_8);
		Sample s = service.sample(name);
		if (s == null)
		{
			sendError(ex, 404, "unknown point: " + name);
			return;
		}
		switch (method)
		{
			case "GET" -> sendJson(ex, 200, sampleJson(s).build());
			case "PUT" -> writePoint(ex, s.point());
			default -> sendError(ex, 405, "method not allowed");
		}
	}

	/**
	 * Handles {@code POST /api/points/{name}/pulse} — a momentary output: set the point active,
	 * hold, then guarantee a reset (see {@link PulseService}). Validation mirrors {@link #writePoint}
	 * (writable kind, register range) plus the hold-time bound; the set/reset itself and its
	 * timing are owned by {@link PulseService}.
	 */
	private void pulsePoint(HttpExchange ex, ModbusPoint p) throws IOException
	{
		if (!p.writable())
		{
			sendError(ex, 400, "point '" + p.name() + "' is read-only (" + p.kind().name() + ")");
			return;
		}
		if (!p.pulseAllowed())
		{
			// Static config-level opt-out (pulse="false") — deliberately loud, never a
			// silent no-op: the caller must not believe a relay fired somewhere.
			sendError(ex, 400, "point '" + p.name() + "' does not allow pulsing");
			return;
		}

		String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		int value;
		int holdMs;
		int restValue;
		try
		{
			OptionalInt valueOpt = Json.intField(body, "value");
			OptionalInt holdOpt = Json.intField(body, "holdMs");
			if (valueOpt.isEmpty() || holdOpt.isEmpty())
			{
				sendError(ex, 400, "body must be {\"value\": N, \"holdMs\": N [, \"restValue\": N]}");
				return;
			}
			value = valueOpt.getAsInt();
			holdMs = holdOpt.getAsInt();
			restValue = Json.intField(body, "restValue").orElse(0);
		}
		catch (IllegalArgumentException e)
		{
			sendError(ex, 400, e.getMessage());
			return;
		}

		if (holdMs < p.minHoldMs() || holdMs > p.maxHoldMs())
		{
			sendError(ex, 400, "holdMs out of range (" + p.minHoldMs() + ".." + p.maxHoldMs() + ") for '" + p.name() + "': " + holdMs);
			return;
		}
		if (p.kind() == RegisterKind.HOLDING_REGISTERS && (outOfRegisterRange(value) || outOfRegisterRange(restValue)))
		{
			sendError(ex, 400, "register value out of range (0..65535)");
			return;
		}

		switch (pulse.pulse(p, value, holdMs, restValue))
		{
			case ACCEPTED -> sendJson(ex, 200, sampleJson(service.sample(p.name())).put("holdMs", holdMs).put("restValue", restValue).build());
			case WRITE_FAILED -> sendError(ex, 503, "Modbus write failed: link down");
			case QUEUE_FULL -> sendError(ex, 409, "too many pending pulses for '" + p.name() + "'");
		}
	}

	private static boolean outOfRegisterRange(int value)
	{
		return value < 0 || value > 0xFFFF;
	}

	private void writePoint(HttpExchange ex, ModbusPoint p) throws IOException
	{
		if (!p.writable())
		{
			sendError(ex, 400, "point '" + p.name() + "' is read-only (" + p.kind().name() + ")");
			return;
		}

		int value;
		try
		{
			value = Json.parseValue(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IllegalArgumentException e)
		{
			sendError(ex, 400, e.getMessage());
			return;
		}

		if (p.kind() == RegisterKind.HOLDING_REGISTERS && (value < 0 || value > 0xFFFF))
		{
			sendError(ex, 400, "register value out of range (0..65535): " + value);
			return;
		}

		if (p.simulate())
		{
			// Simulated point: the write is absorbed by the cache — same validation, same
			// response shape, no wire. Callers cannot tell this from a live write.
			service.update(p.name(), p.kind() == RegisterKind.COILS ? (value != 0 ? 1 : 0) : value);
			log.info("Wrote {} = {} (simulated)", p.name(), value);
			sendJson(ex, 200, sampleJson(service.sample(p.name())).build());
			return;
		}

		try
		{
			if (p.kind() == RegisterKind.COILS)
			{
				controller.writeBit(RegisterKind.COILS, p.address(), value != 0);
			}
			else
			{
				controller.writeRegister(RegisterKind.HOLDING_REGISTERS, p.address(), value);
			}
			int readBack = readBack(p);
			service.update(p.name(), readBack);
			log.info("Wrote {} = {} (read back {})", p.name(), value, readBack);
		}
		catch (Exception e)
		{
			log.warn("Write of '{}' = {} failed: {}", p.name(), value, e.getMessage());
			sendError(ex, 503, "Modbus write failed: " + e.getMessage());
			return;
		}

		sendJson(ex, 200, sampleJson(service.sample(p.name())).build());
	}

	private int readBack(ModbusPoint p) throws Exception
	{
		if (p.kind().bit)
		{
			return controller.readBits(p.kind(), p.address(), 1)[0] ? 1 : 0;
		}
		return controller.readRegisters(p.kind(), p.address(), 1)[0] & 0xFFFF;
	}

	// --- /api/status --------------------------------------------------------------------

	private void handleStatus(HttpExchange ex) throws Exception
	{
		if (!ex.getRequestMethod().equals("GET"))
		{
			sendError(ex, 405, "method not allowed");
			return;
		}
		// simulatedPoints / modbusEnabled are diagnostics for the operator (the web UI tags
		// simulated rows from them); the points payload itself deliberately carries no
		// simulation marker so the client<->bridge conversation is unchanged.
		Json.Arr simulated = Json.arr();
		for (ModbusPoint p : service.points())
		{
			if (p.simulate())
			{
				simulated.add(p.name());
			}
		}
		sendJson(ex, 200, Json.obj()
			.put("appName", CommonBridge.programName)
			.put("appVersion", CommonBridge.version)
			.put("connected", service.isConnected())
			.put("modbusEnabled", cfg.modbusEnabled())
			.put("target", target())
			.put("unitId", cfg.unitId())
			.put("pollIntervalMs", cfg.pollIntervalMs())
			.put("maxHoldMs", cfg.maxHoldMs())
			.put("lastPollMillis", service.lastPollMillis())
			.put("pointCount", service.points().size())
			.putRaw("simulatedPoints", simulated.build())
			.build());
	}

	// --- /api/licence -------------------------------------------------------------------

	/**
	 * Renders the third-party licence list via the bridge-owned {@link Licences} reader.
	 * The client's {@code JLicenseInfo} is deliberately <em>not</em> reused: it has the
	 * client's identity inlined as compile-time constants, so it can never report the
	 * bridge's name/version. {@link Licences} reuses only {@code JXMLDocument} (which has
	 * no such constants) and builds the app entry from {@link com.commander4j.modbusbridge.CommonBridge}.
	 */
	private void handleLicence(HttpExchange ex) throws Exception
	{
		if (!ex.getRequestMethod().equals("GET"))
		{
			sendError(ex, 405, "method not allowed");
			return;
		}
		Json.Arr arr = Json.arr();
		for (Licences.Entry lic : Licences.list())
		{
			arr.add(Json.obj()
				.put("description", lic.description())
				.put("version", lic.version())
				.put("type", lic.type())
				.put("licenceFilename", lic.licenceFilename()));
		}
		sendJson(ex, 200, arr.build());
	}

	// --- /api/log/tail ------------------------------------------------------------------

	/** Returns the backlog of recent log lines; {@code log.html} then follows via SSE. */
	private void handleLogTail(HttpExchange ex) throws Exception
	{
		if (!ex.getRequestMethod().equals("GET"))
		{
			sendError(ex, 405, "method not allowed");
			return;
		}
		int lines = LOG_TAIL_DEFAULT;
		String query = ex.getRequestURI().getQuery();
		if (query != null && query.startsWith("lines="))
		{
			try
			{
				lines = Math.max(1, Math.min(5000, Integer.parseInt(query.substring("lines=".length()))));
			}
			catch (NumberFormatException ignored)
			{
				// keep the default
			}
		}
		Json.Arr arr = Json.arr();
		for (String line : logTailer.tail(lines))
		{
			arr.add(line);
		}
		sendJson(ex, 200, Json.obj().putRaw("lines", arr.build()).build());
	}

	/** The full live-points JSON, shared by GET /api/points and the points SSE stream. */
	private String pointsPayload()
	{
		Json.Arr arr = Json.arr();
		for (Sample s : service.snapshot())
		{
			arr.add(sampleJson(s));
		}
		return Json.obj()
			.put("appName", CommonBridge.programName)
			.put("appVersion", CommonBridge.version)
			.put("connected", service.isConnected())
			.put("target", target())
			.put("unitId", cfg.unitId())
			.put("lastPollMillis", service.lastPollMillis())
			.putRaw("points", arr.build())
			.build();
	}

	// --- static files -------------------------------------------------------------------

	private void handleStatic(HttpExchange ex) throws Exception
	{
		if (!ex.getRequestMethod().equals("GET"))
		{
			sendError(ex, 405, "method not allowed");
			return;
		}
		String path = ex.getRequestURI().getPath();
		String rel = path.equals("/") ? "index.html" : path.substring(1);
		Path target = WEB_ROOT.resolve(rel).normalize();
		if (!target.startsWith(WEB_ROOT))
		{
			sendError(ex, 403, "forbidden");
			return;
		}
		if (!Files.isRegularFile(target))
		{
			sendError(ex, 404, "not found");
			return;
		}
		byte[] body = Files.readAllBytes(target);
		ex.getResponseHeaders().set("Content-Type", contentType(target));
		// Revalidate on every use so a browser never serves a stale page after an upgrade.
		ex.getResponseHeaders().set("Cache-Control", "no-cache");
		ex.sendResponseHeaders(200, body.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(body);
		}
	}

	private static String contentType(Path file)
	{
		String n = file.getFileName().toString().toLowerCase();
		if (n.endsWith(".html"))
		{
			return "text/html; charset=utf-8";
		}
		if (n.endsWith(".css"))
		{
			return "text/css; charset=utf-8";
		}
		if (n.endsWith(".js"))
		{
			return "application/javascript; charset=utf-8";
		}
		if (n.endsWith(".json"))
		{
			return "application/json; charset=utf-8";
		}
		return "application/octet-stream";
	}

	// --- helpers ------------------------------------------------------------------------

	private String target()
	{
		return cfg.host() + ":" + cfg.port();
	}

	private static Json.Obj sampleJson(Sample s)
	{
		ModbusPoint p = s.point();
		Json.Obj o = Json.obj()
			.put("name", p.name())
			.put("kind", p.kind().name())
			.put("address", p.address())
			.put("writable", p.writable())
			.put("bit", p.kind().bit)
			.put("value", s.value())
			.put("valid", s.valid())
			.put("stale", s.stale());
		if (p.writable() && p.pulseAllowed())
		{
			// Per-point pulse hold policy, so the web UI can bound + pre-fill its hold field.
			// Absent entirely for pulse="false" points — no hold policy exists, which is also
			// how clients (and index.html) discover that the point cannot be pulsed.
			o.put("minHoldMs", p.minHoldMs())
			 .put("maxHoldMs", p.maxHoldMs())
			 .put("defaultHoldMs", p.defaultHoldMs());
		}
		return o;
	}

	private void sendJson(HttpExchange ex, int code, String json) throws IOException
	{
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(code, body.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(body);
		}
	}

	private void sendError(HttpExchange ex, int code, String message) throws IOException
	{
		sendJson(ex, code, Json.obj().put("error", message).build());
	}

	private void trySendError(HttpExchange ex, int code, String message)
	{
		try
		{
			sendError(ex, code, message);
		}
		catch (IOException ignored)
		{
			// response already started or socket gone; nothing to do
		}
	}

	private static ThreadFactory daemonFactory()
	{
		AtomicInteger n = new AtomicInteger(1);
		return r ->
		{
			Thread t = new Thread(r, "modbus-bridge-http-" + n.getAndIncrement());
			t.setDaemon(true);
			return t;
		};
	}
}
