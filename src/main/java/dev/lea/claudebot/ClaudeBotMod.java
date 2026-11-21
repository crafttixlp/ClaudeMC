package dev.lea.claudebot;

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
import java.util.Properties;

public class ClaudeBotMod implements ModInitializer {
	private static HttpApi api;
	private static int port = 25599;
	private static String botName = "Claude";

	@Override
	public void onInitialize() {
		loadConfig();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			BotController c = new BotController(server, botName);
			try {
				api = new HttpApi(c, port);
				BotController.LOG.info("ClaudeBot: Schnittstelle läuft auf http://127.0.0.1:{}", port);
			} catch (IOException e) {
				BotController.LOG.error("ClaudeBot: Port {} ist belegt – Schnittstelle nicht gestartet", port, e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			BotController c = BotController.get();
			if (c != null && c.online()) c.despawn();
			if (api != null) { api.stop(); api = null; }
			BotController.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			BotController c = BotController.get();
			if (c != null) c.tick();
		});

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			BotController c = BotController.get();
			if (c != null && !(sender instanceof BotPlayer))
				c.events.add("CHAT <" + sender.getName().getString() + "> " + message.getContent().getString());
		});
		ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
			BotController c = BotController.get();
			if (c == null || overlay) return;
			String s = message.getString();
			if (s.startsWith("<" + c.botName + ">")) return;
			c.events.add("SYSTEM " + s);
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			BotController c = BotController.get();
			if (c != null && entity == c.bot) c.onDamage(source, amount);
			return true;
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				CommandManager.literal("claude")
						.then(CommandManager.literal("spawn").executes(ctx -> {
							ServerCommandSource src = ctx.getSource();
							BotController c = BotController.get();
							if (c == null) return 0;
							String r = c.spawn(src.getWorld(), src.getPosition(), src.getRotation().y);
							src.sendFeedback(() -> Text.literal(r), false);
							return 1;
						}))
						.then(CommandManager.literal("despawn").executes(ctx -> reply(ctx.getSource(), BotController.get().despawn())))
						.then(CommandManager.literal("stop").executes(ctx -> {
							BotController.get().cancelTask("abgebrochen (/claude stop)");
							return reply(ctx.getSource(), "Claude stoppt.");
						}))
						.then(CommandManager.literal("status").executes(ctx -> {
							BotController c = BotController.get();
							return reply(ctx.getSource(), c.online() ? "Aufgabe: " + c.taskStatus() : "Claude ist nicht im Spiel. /claude spawn");
						}))
		));
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
			} else {
				props.setProperty("port", "25599");
				props.setProperty("name", "Claude");
				try (Writer w = Files.newBufferedWriter(p)) { props.store(w, "ClaudeBot – Port der lokalen Schnittstelle und Name des Bots (max. 16 Zeichen, A-Z a-z 0-9 _)"); }
			}
			port = Integer.parseInt(props.getProperty("port", "25599").trim());
			botName = props.getProperty("name", "Claude").trim();
		} catch (Exception e) {
			BotController.LOG.warn("ClaudeBot: Konfiguration nicht lesbar, nehme Standardwerte", e);
		}
	}
}
