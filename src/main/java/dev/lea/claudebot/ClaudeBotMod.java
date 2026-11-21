package dev.lea.claudebot;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public class ClaudeBotMod implements ModInitializer {
	private static HttpApi api;
	private static int port = 25599;

	@Override
	public void onInitialize() {
		loadConfig();
		// Der Bildmodus nutzt AWT nur zum Malen in den Speicher – nie mit Fenster.
		if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			BotRegistry.start(server, FabricLoader.getInstance().getConfigDir());
			try {
				api = new HttpApi(port);
				BotController.LOG.info("ClaudeBot: Schnittstelle läuft auf http://127.0.0.1:{}", port);
			} catch (IOException e) {
				BotController.LOG.error("ClaudeBot: Port {} ist belegt – Schnittstelle nicht gestartet", port, e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (api != null) { api.stop(); api = null; }
			BotRegistry.stop();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (BotController c : BotRegistry.all()) c.tick();
		});

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			if (sender instanceof BotPlayer) return;
			String line = "CHAT <" + sender.getName().getString() + "> " + message.getContent().getString();
			for (BotController c : BotRegistry.all()) c.events.add(line);
		});
		ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
			if (overlay) return;
			String s = message.getString();
			// Bots sprechen über Systemnachrichten "<Name> Text": für die anderen Bots ist das Chat.
			String line = (s.startsWith("<") ? "CHAT " : "SYSTEM ") + s;
			for (BotController c : BotRegistry.all()) {
				if (s.startsWith("<" + c.botName + ">")) continue;
				c.events.add(line);
			}
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof BotPlayer) {
				BotController c = BotRegistry.of(entity);
				if (c != null) c.onDamage(source, amount);
			}
			return true;
		});

		SuggestionProvider<ServerCommandSource> names = (ctx, b) -> {
			for (BotController c : BotRegistry.all()) b.suggest(c.botName);
			return b.buildFuture();
		};
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				CommandManager.literal("claude")
						.then(CommandManager.literal("spawn")
								.executes(ctx -> spawn(ctx, null))
								.then(CommandManager.argument("name", StringArgumentType.word()).suggests(names)
										.executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name")))))
						.then(CommandManager.literal("despawn")
								.executes(ctx -> each(ctx, null, c -> c.despawn()))
								.then(CommandManager.argument("name", StringArgumentType.word()).suggests(names)
										.executes(ctx -> each(ctx, StringArgumentType.getString(ctx, "name"), c -> c.despawn()))))
						.then(CommandManager.literal("stop")
								.executes(ctx -> each(ctx, null, ClaudeBotMod::stopTask))
								.then(CommandManager.argument("name", StringArgumentType.word()).suggests(names)
										.executes(ctx -> each(ctx, StringArgumentType.getString(ctx, "name"), ClaudeBotMod::stopTask))))
						.then(CommandManager.literal("theme")
								.then(CommandManager.argument("name", StringArgumentType.word()).suggests(names)
										.executes(ctx -> theme(ctx, StringArgumentType.getString(ctx, "name"), null))
										.then(CommandManager.argument("text", StringArgumentType.greedyString())
												.executes(ctx -> theme(ctx, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "text"))))))
						.then(CommandManager.literal("list").executes(ctx -> reply(ctx.getSource(), BotRegistry.list())))
						.then(CommandManager.literal("status").executes(ctx -> reply(ctx.getSource(), BotRegistry.list())))
		));
	}

	private static String stopTask(BotController c) {
		c.cancelTask("abgebrochen (/claude stop)");
		return c.botName + " stoppt.";
	}

	/** Ohne Text: Thema anzeigen. Text "-" löscht es. "all" gilt für alle Bots. */
	private static int theme(CommandContext<ServerCommandSource> ctx, String name, String text) {
		return each(ctx, name, c -> {
			if (text == null) {
				String th = BotRegistry.theme(c.botName);
				return c.botName + ": " + (th.isEmpty() ? "kein Thema" : th);
			}
			boolean clear = text.trim().equals("-");
			BotRegistry.setTheme(c.botName, clear ? "" : text);
			c.events.add(clear ? "THEMA gelöscht: Spiel frei nach eigenen Zielen." : "NEUES THEMA von " + ctx.getSource().getName() + ": " + text.trim());
			return c.botName + ": Thema " + (clear ? "gelöscht" : "gesetzt");
		});
	}

	private static int spawn(CommandContext<ServerCommandSource> ctx, String name) {
		ServerCommandSource src = ctx.getSource();
		if (name != null && !BotRegistry.validName(name)) return reply(src, "Ungültiger Name (3–16 Zeichen, A-Z a-z 0-9 _).");
		BotController c = BotRegistry.getOrCreate(name);
		if (c == null) return reply(src, "Bot konnte nicht angelegt werden.");
		return reply(src, c.spawn(src.getWorld(), src.getPosition(), src.getRotation().y));
	}

	/** Führt f für einen Bot aus, für alle bei "all", ohne Namen für den Standard-Bot. */
	private static int each(CommandContext<ServerCommandSource> ctx, String name, java.util.function.Function<BotController, String> f) {
		ServerCommandSource src = ctx.getSource();
		List<BotController> targets;
		if ("all".equalsIgnoreCase(name)) targets = BotRegistry.all();
		else {
			BotController c = BotRegistry.get(name);
			if (c == null) return reply(src, name == null ? "Keine Bots. /claude spawn <name>" : "Bot '" + name + "' gibt es nicht. /claude list");
			targets = List.of(c);
		}
		for (BotController c : targets) reply(src, f.apply(c));
		return targets.size();
	}

	private static int reply(ServerCommandSource src, String msg) {
		src.sendFeedback(() -> Text.literal(msg), false);
		return 1;
	}

	private static void loadConfig() {
		Path p = FabricLoader.getInstance().getConfigDir().resolve("claudebot.properties");
		Properties props = new Properties();
		try {
			if (Files.exists(p)) {
				try (Reader r = Files.newBufferedReader(p)) { props.load(r); }
			}
			boolean changed = false;
			for (String[] kv : new String[][]{{"port", "25599"}, {"name", "Claude"}, {"max_bots", "4"}, {"require_token", "false"}, {"fair_sight", "true"}, {"reflexes", "true"}}) {
				if (!props.containsKey(kv[0])) { props.setProperty(kv[0], kv[1]); changed = true; }
			}
			if (changed) {
				try (Writer w = Files.newBufferedWriter(p)) {
					props.store(w, "ClaudeBot – port: lokale Schnittstelle; name: Standard-Bot (3–16 Zeichen, A-Z a-z 0-9 _); max_bots: Bots gleichzeitig; require_token: Header X-Bot-Token verlangen; fair_sight: Bots sehen nur, was in Sichtlinie liegt; reflexes: Bots wehren sich gegen Monster und essen von selbst");
				}
			}
			port = Integer.parseInt(props.getProperty("port").trim());
			String n = props.getProperty("name").trim();
			if (BotRegistry.validName(n)) BotRegistry.defaultName = n;
			BotRegistry.maxBots = Math.max(1, Integer.parseInt(props.getProperty("max_bots").trim()));
			BotRegistry.reflexes = Boolean.parseBoolean(props.getProperty("reflexes").trim());
			BotRegistry.fairSight = Boolean.parseBoolean(props.getProperty("fair_sight").trim());
			BotRegistry.requireToken = Boolean.parseBoolean(props.getProperty("require_token").trim());
		} catch (Exception e) {
			BotController.LOG.warn("ClaudeBot: Konfiguration nicht lesbar, nehme Standardwerte", e);
		}
	}
}
