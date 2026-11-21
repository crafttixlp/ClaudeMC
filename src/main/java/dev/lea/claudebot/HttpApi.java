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
 *   GET  /bots                         → Liste aller Bots
 *   POST /bots/{name}/cmd?wait=SEK     Körper = Befehlszeile → Ergebnis + neue Ereignisse dieses Bots
 *   GET  /bots/{name}/events?wait=SEK  → wartet auf neue Ereignisse dieses Bots (Chat …)
 *   GET  /bots/{name}/image            → PNG aus Sicht des Bots; Legende im Header X-Legend (URL-kodiert)
 *   POST /cmd, GET /events             → dasselbe für den Standard-Bot
 * Ist require_token=true gesetzt, braucht jeder Bot-Aufruf den Header X-Bot-Token.
 */
public class HttpApi {
	private final HttpServer http;

	public HttpApi(int port) throws IOException {
		http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		http.setExecutor(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "claudebot-http");
			t.setDaemon(true);
			return t;
		}));
		http.createContext("/cmd", ex -> cmd(ex, null));
		http.createContext("/events", ex -> events(ex, null));
		http.createContext("/bots", this::bots);
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

	private void bots(HttpExchange ex) throws IOException {
		String[] p = ex.getRequestURI().getPath().split("/");
		// "", "bots", name, aktion
		if (p.length <= 2) { send(ex, BotRegistry.list()); return; }
		String name = p[2], action = p.length > 3 ? p[3] : "";
		if (!BotRegistry.validName(name)) { send(ex, 400, "Ungültiger Botname (3–16 Zeichen, A-Z a-z 0-9 _)."); return; }
		switch (action) {
			case "cmd" -> cmd(ex, name);
			case "events" -> events(ex, name);
			case "image" -> image(ex, name);
			default -> send(ex, 404, "Unbekannt. Möglich: /bots, /bots/{name}/cmd, /bots/{name}/events, /bots/{name}/image");
		}
	}

	/** Sucht den Bot; null (und Antwort gesendet), wenn es ihn nicht gibt oder das Token fehlt. */
	private BotController find(HttpExchange ex, String name, boolean create) throws IOException {
		BotController c = create ? BotRegistry.getOrCreate(name) : BotRegistry.get(name);
		if (c == null) {
			send(ex, 404, name == null ? "Kein Bot vorhanden. Erst 'spawn' (oder im Spiel /claude spawn <name>)."
					: "Bot '" + name + "' gibt es nicht. Erst 'spawn' senden oder im Spiel /claude spawn " + name + ".");
			return null;
		}
		if (!BotRegistry.tokenOk(c.botName, ex.getRequestHeaders().getFirst("X-Bot-Token"))) {
			send(ex, 403, "Token fehlt oder ist falsch (Header X-Bot-Token, siehe config/claudebot-tokens.properties).");
			return null;
		}
		return c;
	}

	private void cmd(HttpExchange ex, String name) throws IOException {
		Map<String, String> q = query(ex);
		String line = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
		if (line.isEmpty()) line = q.getOrDefault("c", "help");
		String verb = line.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
		BotController c = find(ex, name, verb.equals("spawn") || verb.equals("help") || verb.equals("hilfe"));
		if (c == null) return;
		String out;
		try {
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

	private void image(HttpExchange ex, String name) throws IOException {
		BotController c = find(ex, name, false);
		if (c == null) return;
		try {
			Object r = BotRegistry.server.submit((java.util.function.Supplier<Object>) () -> {
				if (!c.online()) return c.botName + " ist nicht im Spiel.";
				try { return Painter.paint(c); } catch (Exception e) { return "Bild fehlgeschlagen: " + e; }
			}).get(30, TimeUnit.SECONDS);
			if (!(r instanceof Painter.Picture pic)) { send(ex, 409, String.valueOf(r)); return; }
			ex.getResponseHeaders().set("Content-Type", "image/png");
			ex.getResponseHeaders().set("X-Legend", java.net.URLEncoder.encode(pic.legend(), StandardCharsets.UTF_8));
			ex.sendResponseHeaders(200, pic.png().length);
			try (OutputStream os = ex.getResponseBody()) { os.write(pic.png()); }
		} catch (Exception e) {
			send(ex, 500, "Fehler: " + e);
		}
	}

	private void events(HttpExchange ex, String name) throws IOException {
		BotController c = find(ex, name, false);
		if (c == null) return;
		String ev = c.events.await(waitMs(query(ex), 0));
		send(ex, ev.isEmpty() ? "(keine neuen Ereignisse)" : ev);
	}

	private static void send(HttpExchange ex, String body) throws IOException {
		send(ex, 200, body);
	}

	private static void send(HttpExchange ex, int status, String body) throws IOException {
		byte[] b = (body.endsWith("\n") ? body : body + "\n").getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		ex.sendResponseHeaders(status, b.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(b);
		}
	}
}
