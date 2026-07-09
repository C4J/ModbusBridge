package com.commander4j.modbusbridge;

import java.io.File;
import java.util.concurrent.CountDownLatch;

import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.commander4j.modbus.ClientController;
import com.commander4j.modbusbridge.web.WebServer;

/**
 * Headless entry point for the Modbus bridge (steps 1+2: config + poll/reconnect core,
 * no web layer yet).
 *
 * <p>Loads {@code xml/config/config.xml}, wires a {@link PointService} to a
 * {@link PollThread} driving the reused {@link ClientController}, and parks the main
 * thread until shutdown. The JVM is kept alive by the latch rather than the poll thread,
 * which is a daemon.
 *
 * <p><b>Shutdown ordering.</b> Run with {@code -Dlog4j2.shutdownHookEnabled=false} so
 * log4j2 does <em>not</em> register its own hook. This hook then tears down in a fixed
 * order — <em>stop the web server, flush pending pulse resets, stop and join the poll
 * thread, close the Modbus link, then stop log4j2 last</em> — so every teardown event
 * still reaches the log file. The pulse flush runs while the poll thread is still alive so
 * an in-flight reset can still ride a reconnect and land before the link closes (else a
 * relay caught mid-hold would stay energised across a restart). Joining the poll thread
 * before disconnecting avoids a race where a disconnect mid-read would look like a dropped
 * link and trigger a reconnect during shutdown.
 */
public final class Main
{
	private static final Logger log = LoggerFactory.getLogger(Main.class);

	private Main()
	{
	}

	public static void main(String[] args) throws Exception
	{
		File configFile = args.length > 0 ? new File(args[0]) : CommonBridge.configFile;

		System.out.println(CommonBridge.buildTitle(null));

		log.info("{} starting", CommonBridge.buildTitle(null));
		log.info("Loading configuration from {}", configFile.getAbsolutePath());

		BridgeConfig cfg;
		try
		{
			cfg = ConfigStore.load(configFile);
		}
		catch (Exception e)
		{
			log.error("Failed to load configuration: {}", e.getMessage());
			LogManager.shutdown();
			System.exit(2);
			return;
		}

		ClientController controller = new ClientController();
		PointService service = new PointService(cfg.points());
		PollThread poll = new PollThread(controller, service, cfg);
		PulseService pulse = new PulseService(controller, service);

		WebServer web;
		try
		{
			web = new WebServer(cfg, service, controller, pulse);
		}
		catch (Exception e)
		{
			log.error("Failed to start web server on {}:{}: {}", cfg.webHost(), cfg.webPort(), e.getMessage());
			LogManager.shutdown();
			System.exit(3);
			return;
		}

		CountDownLatch stopped = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() ->
		{
			log.info("Shutdown requested");
			web.stop();               // stop accepting requests first
			pulse.flush();            // force pending pulse resets to land BEFORE the link closes
			poll.shutdown();          // stop + join the poll thread (it disconnects on stop)
			try
			{
				controller.disconnect(); // belt-and-braces; no-op if already closed
			}
			catch (Exception e)
			{
				// nothing useful to do during shutdown
			}
			log.info("{} stopped", CommonBridge.programName);
			LogManager.shutdown();    // log4j2 last, so the lines above are flushed
			stopped.countDown();
		}, "modbus-bridge-shutdown"));

		if (cfg.modbusEnabled())
		{
			poll.start();
		}
		else
		{
			// <modbus enabled="false">: the device is known to be out of service, so never
			// connect (no reconnect noise in the log). Real points stay stale and writes
			// return 503; simulated points are fully operational. poll.shutdown() in the
			// hook is harmless on a never-started thread.
			log.warn("Modbus link DISABLED in configuration — not connecting to {}:{}; only simulated points are live", cfg.host(), cfg.port());
		}
		web.start();

		try
		{
			stopped.await();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
}
