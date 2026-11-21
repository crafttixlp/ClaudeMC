package dev.lea.claudebot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lokale Text-Schnittstelle (nur 127.0.0.1).
 *   POST /cmd?wait=SEK   Körper = Befehlszeile → Ergebnis + neue Ereignisse
 *   GET  /events?wait=SEK                        → wartet auf neue Ereignisse (Chat …)
 */
public class HttpApi {
	private final HttpServer http;
	private final BotController c;

	public HttpApi(BotController c, int port) throws IOException {
		this.c = c;
		http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		http.setExecutor(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "claudebot-http");
			t.setDaemon(true);
			return t;
		}));
		http.createContext("/cmd", this::cmd);
		http.createContext("/events", this::events);
		http.start();
	}

	public void stop() {
		http.stop(0);
	}

	private static Map<String, String> query(HttpExchange ex) {
		Map<String, String> m = new HashMap<>();
		String q = ex.getRequestURI().getRawQuery();
		if (q == null) return m;
		for (String kv : q.split("&")) {
			int i = kv.indexOf('=');
			if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
		}
		return m;
	}

	private static long waitMs(Map<String, String> q, long def) {
		try {
			return Math.max(0, Math.min(600, Long.parseLong(q.getOrDefault("wait", String.valueOf(def))))) * 1000L;
		} catch (NumberFormatException e) {
			return def * 1000L;
		}
	}

	private void cmd(HttpExchange ex) throws IOException {
		String out;
		try {
			Map<String, String> q = query(ex);
			String line = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (line.isEmpty()) line = q.getOrDefault("c", "help");
			final String l = line;
			Object r = c.server.submit((java.util.function.Supplier<Object>) () -> c.execute(l)).get(15, TimeUnit.SECONDS);
			if (r instanceof BotController.Task t) {
				String res = t.await(waitMs(q, 60));
				out = res != null ? res
						: "Läuft noch: " + t.name() + ". Das Ergebnis kommt später als Ereignis ('mc wait' oder 'mc events'). 'mc stop' bricht ab.";
			} else {
				out = String.valueOf(r);
			}
			if (c.server.isPaused()) out += "\n(ACHTUNG: Das Spiel ist pausiert – im Spiel das Menü schließen oder die Welt für LAN öffnen.)";
		} catch (Exception e) {
			out = "Fehler: " + e;
		}
		String ev = c.events.drain();
		if (!ev.isEmpty()) out += "\n--- Neue Ereignisse ---\n" + ev;
		send(ex, out);
	}

	private void events(HttpExchange ex) throws IOException {
		String ev = c.events.await(waitMs(query(ex), 0));
		send(ex, ev.isEmpty() ? "(keine neuen Ereignisse)" : ev);
	}

	private static void send(HttpExchange ex, String body) throws IOException {
		byte[] b = (body.endsWith("\n") ? body : body + "\n").getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		ex.sendResponseHeaders(200, b.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(b);
		}
	}
}
