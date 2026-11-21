package dev.lea.claudebot;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Alle Bots dieser Welt. Je Bot ein {@link BotController} mit eigenem Zustand, Ereignissen und Token. */
public final class BotRegistry {
	private static final Map<String, BotController> BOTS = new LinkedHashMap<>();
	private static final Properties TOKENS = new Properties();
	private static final Properties THEMES = new Properties();
	private static Path tokenFile, themeFile;

	static volatile MinecraftServer server;
	static int maxBots = 4;
	static String defaultName = "Claude";
	static boolean requireToken = false;
	/** true: Bots sehen nur, was in Sichtlinie liegt (kein Blick durch Wände). */
	static boolean fairSight = true;
	/** true: Bots wehren sich sofort gegen Monster und essen im Leerlauf von selbst. */
	static boolean reflexes = true;

	private BotRegistry() {}

	static synchronized void start(MinecraftServer s, Path configDir) {
		server = s;
		tokenFile = configDir.resolve("claudebot-tokens.properties");
		themeFile = configDir.resolve("claudebot-themes.properties");
		THEMES.clear();
		if (Files.exists(themeFile)) {
			try (Reader r = Files.newBufferedReader(themeFile)) { THEMES.load(r); } catch (Exception e) {
				BotController.LOG.warn("ClaudeBot: Themen-Datei nicht lesbar", e);
			}
		}
		TOKENS.clear();
		if (Files.exists(tokenFile)) {
			try (Reader r = Files.newBufferedReader(tokenFile)) { TOKENS.load(r); } catch (Exception e) {
				BotController.LOG.warn("ClaudeBot: Token-Datei nicht lesbar", e);
			}
		}
	}

	static synchronized void stop() {
		for (BotController c : BOTS.values()) if (c.online()) c.despawn();
		BOTS.clear();
		server = null;
	}

	public static boolean validName(String name) {
		return name != null && name.matches("[A-Za-z0-9_]{3,16}");
	}

	private static String key(String name) { return name.toLowerCase(Locale.ROOT); }

	/** Bot mit diesem Namen; ohne Namen der Standard-Bot, sonst der erste. Null, wenn es keinen gibt. */
	public static synchronized BotController get(String name) {
		if (name != null && !name.isEmpty()) return BOTS.get(key(name));
		BotController c = BOTS.get(key(defaultName));
		if (c != null) return c;
		return BOTS.isEmpty() ? null : BOTS.values().iterator().next();
	}

	/** Legt den Bot an, falls es ihn noch nicht gibt. Null bei ungültigem Namen oder ohne Server. */
	public static synchronized BotController getOrCreate(String name) {
		if (name == null || name.isEmpty()) name = defaultName;
		if (server == null || !validName(name)) return null;
		BotController c = BOTS.get(key(name));
		if (c == null) {
			c = new BotController(server, name);
			BOTS.put(key(name), c);
			token(name);
		}
		return c;
	}

	public static synchronized List<BotController> all() {
		return new ArrayList<>(BOTS.values());
	}

	public static synchronized int onlineCount() {
		int n = 0;
		for (BotController c : BOTS.values()) if (c.online()) n++;
		return n;
	}

	/** Der Controller, dem dieses Wesen gehört (oder null). */
	public static synchronized BotController of(Entity e) {
		for (BotController c : BOTS.values()) if (c.bot == e) return c;
		if (e instanceof BotPlayer b) return BOTS.get(key(b.getGameProfile().getName()));
		return null;
	}

	/** Zugangstoken des Bots; wird beim ersten Mal erzeugt und in config/claudebot-tokens.properties gespeichert. */
	public static synchronized String token(String name) {
		String t = TOKENS.getProperty(key(name));
		if (t == null) {
			byte[] b = new byte[16];
			new SecureRandom().nextBytes(b);
			t = HexFormat.of().formatHex(b);
			TOKENS.setProperty(key(name), t);
			if (tokenFile != null) {
				try (Writer w = Files.newBufferedWriter(tokenFile)) {
					TOKENS.store(w, "ClaudeBot – Zugangstoken je Bot (Header X-Bot-Token). Nicht weitergeben.");
				} catch (Exception e) {
					BotController.LOG.warn("ClaudeBot: Token-Datei nicht schreibbar", e);
				}
			}
		}
		return t;
	}

	public static synchronized boolean tokenOk(String name, String given) {
		if (!requireToken) return true;
		String t = TOKENS.getProperty(key(name));
		return t != null && t.equals(given);
	}

	/** Thema des Bots (leer = freies Spiel). Bleibt über Neustarts erhalten. */
	public static synchronized String theme(String name) {
		return THEMES.getProperty(key(name), "");
	}

	public static synchronized void setTheme(String name, String theme) {
		if (theme == null || theme.isBlank()) THEMES.remove(key(name));
		else THEMES.setProperty(key(name), theme.trim());
		if (themeFile != null) {
			try (Writer w = Files.newBufferedWriter(themeFile)) {
				THEMES.store(w, "ClaudeBot – Thema je Bot (/claude theme <name|all> <text>)");
			} catch (Exception e) {
				BotController.LOG.warn("ClaudeBot: Themen-Datei nicht schreibbar", e);
			}
		}
	}

	/** Eine Zeile je Bot, für /claude list und GET /bots. */
	public static String list() {
		List<BotController> l = all();
		if (l.isEmpty()) return "Keine Bots. /claude spawn <name>";
		StringBuilder sb = new StringBuilder();
		for (BotController c : l) {
			sb.append(c.botName).append(": ");
			if (c.online()) sb.append("bei ").append(Describe.pos(c.bot.getBlockPos())).append(", Aufgabe: ").append(c.taskStatus());
			else sb.append("nicht im Spiel");
			String th = theme(c.botName);
			if (!th.isEmpty()) sb.append(", Thema: ").append(th);
			sb.append('\n');
		}
		return sb.toString().trim();
	}
}
