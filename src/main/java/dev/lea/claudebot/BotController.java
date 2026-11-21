package dev.lea.claudebot;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

import static dev.lea.claudebot.Describe.id;
import static dev.lea.claudebot.Describe.pos;

/** Steuert den Bot: Befehle, laufende Aufgaben, Ereignisse. Läuft im Server-Thread. */
public class BotController {
	static final Logger LOG = LoggerFactory.getLogger("claudebot");
	private static volatile BotController INSTANCE;

	public static BotController get() { return INSTANCE; }
	static void clear() { INSTANCE = null; }

	public final MinecraftServer server;
	public final EventLog events = new EventLog();
	public final String botName;
	public BotPlayer bot;
	private Task task;
	private boolean removeNextTick;
	private int respawnIn = -1;
	private int lastHurtTick = -100;
	private boolean hungerWarned, sleepHinted;

	public BotController(MinecraftServer server, String botName) {
		this.server = server;
		this.botName = botName;
		INSTANCE = this;
	}

	public boolean online() { return bot != null && !bot.isRemoved(); }
	ServerWorld world() { return bot.getServerWorld(); }

	// ------------------------------------------------------------------ Lebenszyklus

	public String spawn(ServerWorld w, Vec3d p, float yaw) {
		if (online()) return botName + " ist schon im Spiel bei " + pos(bot.getBlockPos());
		if (server.getPlayerManager().getPlayer(botName) != null) return "Ein Spieler namens " + botName + " ist schon online.";
		GameProfile profile = new GameProfile(Uuids.getOfflinePlayerUuid(botName), botName);
		BotPlayer b = new BotPlayer(server, w, profile);
		server.getPlayerManager().onPlayerConnect(new FakeConnection(), b, ConnectedClientData.createDefault(profile, false));
		b.teleport(w, p.x, p.y, p.z, yaw, 0f);
		b.unsetRemoved();
		EntityAttributeInstance step = b.getAttributeInstance(EntityAttributes.GENERIC_STEP_HEIGHT);
		if (step != null) step.setBaseValue(0.6);
		bot = b;
		return botName + " ist bei " + pos(b.getBlockPos()) + " erschienen.";
	}

	public String despawn() {
		if (!online()) return botName + " ist nicht im Spiel.";
		cancelTask("abgebrochen: " + botName + " hat das Spiel verlassen");
		bot.networkHandler.onDisconnected(new DisconnectionInfo(Text.literal("ClaudeBot beendet")));
		bot = null;
		return botName + " hat das Spiel verlassen (Inventar bleibt gespeichert).";
	}

	void onBotDied(BotPlayer b, String msg) {
		events.add("TOD: " + msg + ". Deine Items liegen bei " + pos(b.getBlockPos()) + ". Respawn am Weltspawn in 3 Sekunden.");
		cancelTask("abgebrochen: gestorben");
		removeNextTick = true;
		respawnIn = 60;
	}

	public void tick() {
		if (removeNextTick) {
			removeNextTick = false;
			if (bot != null) {
				bot.networkHandler.onDisconnected(new DisconnectionInfo(Text.literal("gestorben")));
				bot = null;
			}
		}
		if (respawnIn > 0 && --respawnIn == 0) {
			respawnIn = -1;
			ServerWorld ow = server.getOverworld();
			BlockPos sp = ow.getSpawnPos();
			int y = ow.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sp.getX(), sp.getZ());
			events.add("Respawn: " + spawn(ow, new Vec3d(sp.getX() + 0.5, y, sp.getZ() + 0.5), 0f));
		}
		if (!online()) {
			if (task != null) { task.complete("abgebrochen: " + botName + " ist nicht im Spiel"); task = null; }
			return;
		}
		autoPickup();
		watchHungerAndSleep();
		if (task != null) {
			try {
				task.tick();
			} catch (Exception e) {
				LOG.error("Aufgabe fehlgeschlagen", e);
				task.complete("Fehler: " + e);
			}
			if (task != null && task.done) task = null;
		}
	}

	private void autoPickup() {
		for (ItemEntity ie : world().getEntitiesByClass(ItemEntity.class, bot.getBoundingBox().expand(1.0, 0.5, 1.0),
				e -> e.isAlive() && !e.cannotPickup())) {
			ie.onPlayerCollision(bot);
		}
	}

	private void watchHungerAndSleep() {
		if (server.getTicks() % 40 != 0) return;
		int food = bot.getHungerManager().getFoodLevel();
		if (food <= 6 && !hungerWarned) { hungerWarned = true; events.add("WARNUNG: Hunger " + food + "/20 – iss etwas ('eat')."); }
		if (food > 10) hungerWarned = false;
		boolean someoneSleeps = false;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (p != bot && p.isSleeping()) someoneSleeps = true;
		}
		if (someoneSleeps && !bot.isSleeping() && !sleepHinted) {
			sleepHinted = true;
			events.add("Ein Spieler liegt im Bett. Die Nacht wird nur übersprungen, wenn du auch schläfst ('sleep').");
		}
		if (!someoneSleeps) sleepHinted = false;
	}

	void onDamage(DamageSource src, float amount) {
		if (amount <= 0 || server.getTicks() - lastHurtTick < 10) return;
		lastHurtTick = server.getTicks();
		Entity a = src.getAttacker();
		events.add(String.format(Locale.ROOT, "SCHADEN: %.1f durch %s%s (Leben vorher %.1f/20)", amount, src.getName(),
				a != null ? " von " + a.getName().getString() + " (#" + a.getId() + ")" : "", bot.getHealth()));
	}

	void cancelTask(String why) {
		if (task != null) { task.complete(why); task = null; }
		if (bot != null) bot.setSneaking(false);
	}

	String taskStatus() {
		return task == null ? "keine" : task.name + " (läuft seit " + (server.getTicks() - task.startTick) / 20 + " s)";
	}

	// ------------------------------------------------------------------ Befehle

	/** Führt eine Befehlszeile aus. Ergebnis: String (sofort) oder Task (läuft über mehrere Ticks). */
	public Object execute(String line) {
		String[] a = line.trim().split("\\s+");
		if (a.length == 0 || a[0].isEmpty()) return help();
		String v = a[0].toLowerCase(Locale.ROOT);
		try {
			switch (v) {
				case "help", "hilfe": return help();
				case "spawn": return cmdSpawn(a);
				case "despawn": return despawn();
			}
			if (!online()) return botName + " ist nicht im Spiel. Erst 'spawn' (oder im Spiel /claude spawn).";
			switch (v) {
				case "state", "status", "s": return Describe.state(this);
				case "inv", "inventory": return Describe.inventory(bot);
				case "nearby": return Describe.nearby(bot, arg(a, 1, 24, 64), 40);
				case "scan": return Describe.scan(bot, arg(a, 1, 10, 32));
				case "map": {
					int r = arg(a, 1, 10, 24);
					int[] layers = a.length > 2 ? Arrays.stream(a, 2, a.length).mapToInt(Integer::parseInt).toArray() : new int[]{1, 0, -1};
					return Describe.map(bot, r, layers);
				}
				case "top": return Describe.top(bot, arg(a, 1, 12, 24));
				case "say", "chat": {
					String msg = line.trim().substring(a[0].length()).trim();
					if (msg.isEmpty()) return "Was soll ich sagen?";
					server.getPlayerManager().broadcast(Text.literal("<" + botName + "> " + msg), false);
					return "Gesagt: " + msg;
				}
				case "stop": cancelTask("abgebrochen (stop)"); return "Gestoppt.";
				case "look": {
					if (a.length >= 4) { lookAt(Vec3d.ofCenter(parsePos(a, 1))); return "Schaue nach " + pos(parsePos(a, 1)); }
					PlayerEntity p = player(a[1]);
					if (p == null) return "Spieler nicht gefunden: " + a[1];
					lookAt(p.getEyePos());
					return "Schaue zu " + p.getName().getString();
				}
				case "select": {
					int s = Integer.parseInt(a[1]);
					if (s < 0 || s > 8) return "Slot 0-8";
					bot.getInventory().selectedSlot = s;
					return "Hand: " + Describe.stack(bot.getMainHandStack());
				}
				case "equip": return equip(item(a[1]));
				case "drop": return drop(item(a[1]), a.length > 2 ? Integer.parseInt(a[2]) : 64, 100);
				case "craft": return craft(item(a[1]), a.length > 2 ? Integer.parseInt(a[2]) : 1);
				case "recipe", "rezept": return recipe(item(a[1]));
				case "wake": bot.wakeUp(); return "Aufgewacht.";
				case "container", "open": {
					BlockPos t = parsePos(a, 1);
					return start(new ReachTask("container " + pos(t), t, false, () -> showContainer(t)));
				}
				case "take": {
					BlockPos t = parsePos(a, 1);
					Item it = a.length > 4 && !a[4].equals("all") ? item(a[4]) : null;
					int n = a.length > 5 ? Integer.parseInt(a[5]) : 9999;
					return start(new ReachTask("take", t, false, () -> take(t, it, n)));
				}
				case "put", "fuel": {
					BlockPos t = parsePos(a, 1);
					Item it = item(a[4]);
					int n = a.length > 5 ? Integer.parseInt(a[5]) : 9999;
					boolean fuel = v.equals("fuel");
					return start(new ReachTask(v, t, false, () -> put(t, it, n, fuel)));
				}
				case "goto", "go": {
					if (a.length >= 4) {
						BlockPos t = parsePos(a, 1);
						return start(new GotoTask("goto " + pos(t), t, 1.5, null, false));
					}
					PlayerEntity p = player(a[1]);
					if (p == null) return "Spieler nicht gefunden: " + a[1];
					return start(new GotoTask("goto " + p.getName().getString(), p.getBlockPos(), 2.5, p, false));
				}
				case "follow": {
					PlayerEntity p = player(a[1]);
					if (p == null) return "Spieler nicht gefunden: " + a[1];
					return start(new GotoTask("follow " + p.getName().getString(), p.getBlockPos(), 3.0, p, true));
				}
				case "mine", "mine!", "dig": {
					boolean force = v.equals("mine!");
					if (a.length >= 4 && isNumberish(a[1])) {
						BlockPos t = parsePos(a, 1);
						return start(new MineTask(null, t, 1, true));
					}
					Block b = block(a[1]);
					int n = a.length > 2 ? Integer.parseInt(a[2]) : 1;
					return start(new MineTask(b, null, n, force));
				}
				case "place": {
					Item it = item(a[1]);
					BlockPos t = parsePos(a, 2);
					return start(new ReachTask("place " + id(new ItemStack(it)), t, true, () -> place(it, t)));
				}
				case "use": {
					BlockPos t = parsePos(a, 1);
					return start(new ReachTask("use " + pos(t), t, false, () -> useBlock(t)));
				}
				case "sleep": {
					BlockPos bed = nearestBlock(s -> s.getBlock() instanceof BedBlock, 12);
					if (bed == null) return "Kein Bett in 12 Blöcken.";
					return start(new ReachTask("sleep", bed, false, () -> {
						String r = useBlock(bed);
						return bot.isSleeping() ? "Schläft im Bett bei " + pos(bed) : r + " – konnte nicht schlafen (Tag? Monster in der Nähe?)";
					}));
				}
				case "eat": {
					Item it = a.length > 1 ? item(a[1]) : null;
					return start(new UseItemTask("eat", s -> it != null ? s.isOf(it) : isGoodFood(s)));
				}
				case "useitem": return start(new UseItemTask("useitem", null));
				case "attack", "kill": {
					String t = a[1];
					int n = a.length > 2 ? Integer.parseInt(a[2]) : 1;
					return start(new AttackTask(t, n));
				}
				case "give": {
					PlayerEntity p = player(a[1]);
					if (p == null) return "Spieler nicht gefunden: " + a[1];
					Item it = item(a[2]);
					int n = a.length > 3 ? Integer.parseInt(a[3]) : 64;
					return start(new GiveTask(p, it, n));
				}
				case "collect": return start(new CollectTask(arg(a, 1, 8, 16)));
				default: return "Unbekannter Befehl '" + v + "'.\n" + help();
			}
		} catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
			return "Fehler: " + (e.getMessage() != null ? e.getMessage() : "fehlende Argumente") + " (siehe 'help')";
		}
	}

	private Object cmdSpawn(String[] a) {
		if (a.length >= 4) {
			ServerWorld w = server.getOverworld();
			double x = Double.parseDouble(a[1]), y = Double.parseDouble(a[2]), z = Double.parseDouble(a[3]);
			return spawn(w, new Vec3d(x, y, z), 0f);
		}
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (!(p instanceof BotPlayer)) return spawn(p.getServerWorld(), p.getPos(), p.getYaw());
		}
		ServerWorld ow = server.getOverworld();
		BlockPos sp = ow.getSpawnPos();
		return spawn(ow, new Vec3d(sp.getX() + 0.5, ow.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sp.getX(), sp.getZ()), sp.getZ() + 0.5), 0f);
	}

	static String help() {
		return """
				Befehle (Koordinaten: x y z, auch ~ für deine Position, z.B. ~ ~-1 ~):
				  Info:    state | inv | nearby [r] | scan [r] | map [r] [ebenen…] | top [r] | recipe <item>
				  Chat:    say <text>
				  Laufen:  goto <x y z | spieler> | follow <spieler> | stop | look <x y z | spieler>
				  Abbauen: mine <x y z> | mine <block> [anzahl] | mine! <block> [anzahl] (auch ohne passendes Werkzeug)
				  Bauen:   place <item> <x y z> | use <x y z> (Rechtsklick: Tür, Hebel, Knopf…) | sleep | wake
				  Items:   craft <item> [anzahl] | equip <item> | select <0-8> | drop <item> [anzahl]
				           give <spieler> <item> [anzahl] | collect [r] | eat [item] | useitem
				  Kisten/Ofen: container <x y z> | take <x y z> [item|all] [anzahl]
				           put <x y z> <item> [anzahl] | fuel <x y z> <item> [anzahl]
				  Kampf:   attack <#id | mobtyp | hostile> [anzahl]
				  Sonst:   spawn [x y z] | despawn
				Items/Blöcke als Minecraft-ID ohne 'minecraft:', z.B. oak_log, cobblestone, iron_pickaxe.
				""";
	}

	// ------------------------------------------------------------------ Parsen

	private static boolean isNumberish(String s) {
		return s.startsWith("~") || s.matches("-?\\d+(\\.\\d+)?");
	}

	private int arg(String[] a, int i, int def, int max) {
		if (a.length <= i) return def;
		return Math.max(1, Math.min(max, Integer.parseInt(a[i])));
	}

	BlockPos parsePos(String[] a, int i) {
		if (a.length < i + 3) throw new IllegalArgumentException("Koordinaten x y z fehlen");
		BlockPos b = bot.getBlockPos();
		return new BlockPos(coord(a[i], b.getX()), coord(a[i + 1], b.getY()), coord(a[i + 2], b.getZ()));
	}

	private static int coord(String s, int base) {
		if (s.startsWith("~")) return base + (s.length() > 1 ? (int) Math.floor(Double.parseDouble(s.substring(1))) : 0);
		return (int) Math.floor(Double.parseDouble(s));
	}

	static Identifier ident(String s) {
		Identifier id = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s.toLowerCase(Locale.ROOT));
		if (id == null) throw new IllegalArgumentException("ungültige ID " + s);
		return id;
	}

	static Item item(String s) {
		Identifier id = ident(s);
		if (!Registries.ITEM.containsId(id)) throw new IllegalArgumentException("unbekanntes Item '" + s + "'");
		return Registries.ITEM.get(id);
	}

	static Block block(String s) {
		Identifier id = ident(s);
		if (!Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("unbekannter Block '" + s + "'");
		return Registries.BLOCK.get(id);
	}

	PlayerEntity player(String name) {
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
		if (p == null) {
			for (ServerPlayerEntity q : server.getPlayerManager().getPlayerList())
				if (q.getName().getString().equalsIgnoreCase(name)) p = q;
		}
		return p;
	}

	// ------------------------------------------------------------------ Hilfen

	void lookAt(Vec3d t) {
		Vec3d e = bot.getEyePos();
		double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
		bot.setYaw(yaw);
		bot.setHeadYaw(yaw);
		bot.bodyYaw = yaw;
		bot.setPitch(pitch);
	}

	boolean inReach(BlockPos t) {
		return bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(t)) <= 4.6 * 4.6;
	}

	static boolean reachableFrom(BlockPos p, BlockPos t) {
		Vec3d eye = new Vec3d(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
		return eye.squaredDistanceTo(Vec3d.ofCenter(t)) <= 4.2 * 4.2 && !p.equals(t) && !p.up().equals(t);
	}

	int findSlot(Predicate<ItemStack> p) {
		PlayerInventory inv = bot.getInventory();
		if (p.test(inv.main.get(inv.selectedSlot))) return inv.selectedSlot;
		for (int i = 0; i < inv.main.size(); i++) if (!inv.main.get(i).isEmpty() && p.test(inv.main.get(i))) return i;
		return -1;
	}

	void selectSlot(int slot) {
		PlayerInventory inv = bot.getInventory();
		if (slot < 9) { inv.selectedSlot = slot; return; }
		ItemStack a = inv.main.get(inv.selectedSlot);
		inv.main.set(inv.selectedSlot, inv.main.get(slot));
		inv.main.set(slot, a);
	}

	void selectBestTool(BlockState s) {
		PlayerInventory inv = bot.getInventory();
		int best = -1;
		double bestScore = score(inv.main.get(inv.selectedSlot), s);
		for (int i = 0; i < inv.main.size(); i++) {
			double sc = score(inv.main.get(i), s);
			if (sc > bestScore + 0.01) { bestScore = sc; best = i; }
		}
		if (best >= 0) selectSlot(best);
	}

	private static double score(ItemStack st, BlockState s) {
		if (st.isEmpty()) return 1;
		double sc = st.getMiningSpeedMultiplier(s);
		if (s.isToolRequired() && st.isSuitableFor(s)) sc += 100;
		if (st.isDamageable() && st.getMaxDamage() - st.getDamage() <= 1) sc = 0;
		return sc;
	}

	void selectWeapon() {
		PlayerInventory inv = bot.getInventory();
		int best = -1;
		double bestScore = 0;
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack st = inv.main.get(i);
			double sc = 0;
			if (st.getItem() instanceof SwordItem sw) sc = 4 + sw.getMaterial().getAttackDamage();
			else if (st.getItem() instanceof AxeItem ax) sc = 3 + ax.getMaterial().getAttackDamage();
			else if (st.getItem() instanceof ToolItem t) sc = 1 + t.getMaterial().getAttackDamage() * 0.5;
			if (sc > bestScore) { bestScore = sc; best = i; }
		}
		if (best >= 0) selectSlot(best);
	}

	static final Set<String> BAD_FOOD = Set.of("rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chorus_fruit", "suspicious_stew");

	static boolean isGoodFood(ItemStack s) {
		return s.get(DataComponentTypes.FOOD) != null && !BAD_FOOD.contains(id(s));
	}

	BlockPos nearestBlock(Predicate<BlockState> p, int r) {
		BlockPos c = bot.getBlockPos();
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.iterate(c.add(-r, -r, -r), c.add(r, r, r))) {
			if (!p.test(world().getBlockState(q))) continue;
			double d = q.getSquaredDistance(c);
			if (d < bd) { bd = d; best = q.toImmutable(); }
		}
		return best;
	}

	void spawnDrop(ItemStack st, int pickupDelay) {
		Vec3d look = bot.getRotationVector();
		ItemEntity e = new ItemEntity(world(), bot.getX(), bot.getEyeY() - 0.3, bot.getZ(), st);
		e.setVelocity(look.multiply(0.3));
		e.setPickupDelay(pickupDelay);
		world().spawnEntity(e);
	}

	String invSummary(Item it) {
		int n = 0;
		for (ItemStack s : bot.getInventory().main) if (s.isOf(it)) n += s.getCount();
		return n + "x " + id(new ItemStack(it));
	}

	// ------------------------------------------------------------------ Sofort-Aktionen

	String equip(Item it) {
		int slot = findSlot(s -> s.isOf(it));
		if (slot < 0) return "Nicht im Inventar: " + id(new ItemStack(it));
		PlayerInventory inv = bot.getInventory();
		ItemStack s = inv.main.get(slot);
		EquipmentSlot es = bot.getPreferredEquipmentSlot(s);
		if (es.isArmorSlot() || es == EquipmentSlot.OFFHAND) {
			ItemStack old = bot.getEquippedStack(es).copy();
			bot.equipStack(es, s.copy());
			inv.main.set(slot, old);
			return "Angelegt (" + es.getName() + "): " + Describe.stack(bot.getEquippedStack(es));
		}
		selectSlot(slot);
		return "In der Hand: " + Describe.stack(bot.getMainHandStack());
	}

	String drop(Item it, int count, int delay) {
		int left = count;
		for (ItemStack s : bot.getInventory().main) {
			if (left <= 0) break;
			if (!s.isOf(it)) continue;
			int n = Math.min(left, s.getCount());
			spawnDrop(s.split(n), delay);
			left -= n;
		}
		int dropped = count - left;
		return dropped == 0 ? "Nicht im Inventar: " + id(new ItemStack(it)) : "Fallen gelassen: " + dropped + "x " + id(new ItemStack(it));
	}

	String place(Item it, BlockPos t) {
		int slot = findSlot(s -> s.isOf(it));
		if (slot < 0) return "Nicht im Inventar: " + id(new ItemStack(it));
		ServerWorld w = world();
		BlockState before = w.getBlockState(t);
		if (!before.isReplaceable()) return "Dort ist schon " + id(before) + " bei " + pos(t);
		selectSlot(slot);
		bot.setSneaking(true);
		try {
			for (Direction d : Direction.values()) {
				BlockPos n = t.offset(d);
				BlockState ns = w.getBlockState(n);
				if (ns.isAir() || ns.isReplaceable()) continue;
				Vec3d hit = Vec3d.ofCenter(t).add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
				lookAt(hit);
				bot.interactionManager.interactBlock(bot, w, bot.getMainHandStack(), Hand.MAIN_HAND,
						new BlockHitResult(hit, d.getOpposite(), n, false));
				if (w.getBlockState(t) != before) {
					bot.swingHand(Hand.MAIN_HAND);
					return "Platziert: " + id(w.getBlockState(t)) + " bei " + pos(t) + " (noch " + invSummary(it) + ")";
				}
			}
		} finally {
			bot.setSneaking(false);
		}
		return "Konnte nicht platzieren bei " + pos(t) + " (kein fester Nachbarblock, oder jemand steht im Weg)";
	}

	String useBlock(BlockPos t) {
		ServerWorld w = world();
		Direction side = Direction.getFacing(bot.getEyePos().subtract(Vec3d.ofCenter(t)));
		Vec3d hit = Vec3d.ofCenter(t).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
		lookAt(hit);
		String before = id(w.getBlockState(t));
		ActionResult r = bot.interactionManager.interactBlock(bot, w, bot.getMainHandStack(), Hand.MAIN_HAND,
				new BlockHitResult(hit, side, t, false));
		bot.swingHand(Hand.MAIN_HAND);
		if (bot.currentScreenHandler != bot.playerScreenHandler) bot.closeHandledScreen();
		return "Rechtsklick auf " + before + " bei " + pos(t) + ": " + r + " (jetzt: " + w.getBlockState(t) + ")";
	}

	// ------------------------------------------------------------------ Behälter

	Inventory containerAt(BlockPos t) {
		ServerWorld w = world();
		BlockState s = w.getBlockState(t);
		if (s.getBlock() instanceof ChestBlock cb) {
			Inventory inv = ChestBlock.getInventory(cb, s, w, t, true);
			if (inv != null) return inv;
		}
		BlockEntity be = w.getBlockEntity(t);
		return be instanceof Inventory i ? i : null;
	}

	String showContainer(BlockPos t) {
		Inventory inv = containerAt(t);
		if (inv == null) return "Kein Behälter bei " + pos(t) + " (" + id(world().getBlockState(t)) + ")";
		boolean furnace = inv instanceof AbstractFurnaceBlockEntity;
		StringBuilder sb = new StringBuilder(id(world().getBlockState(t)) + " bei " + pos(t) + ":\n");
		boolean any = false;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (furnace) {
				sb.append("  ").append(i == 0 ? "Eingang" : i == 1 ? "Brennstoff" : "Ergebnis").append(": ").append(Describe.stack(s)).append('\n');
				any = true;
			} else if (!s.isEmpty()) {
				sb.append("  [").append(i).append("] ").append(Describe.stack(s)).append('\n');
				any = true;
			}
		}
		if (!any) sb.append("  leer\n");
		return sb.toString();
	}

	String take(BlockPos t, Item filter, int count) {
		Inventory inv = containerAt(t);
		if (inv == null) return "Kein Behälter bei " + pos(t);
		boolean furnace = inv instanceof AbstractFurnaceBlockEntity;
		int left = count, moved = 0;
		Map<String, Integer> got = new LinkedHashMap<>();
		for (int i = 0; i < inv.size() && left > 0; i++) {
			if (furnace && filter == null && i != 2) continue;
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || (filter != null && !s.isOf(filter))) continue;
			int n = Math.min(left, s.getCount());
			ItemStack part = s.copyWithCount(n);
			bot.getInventory().insertStack(part);
			int m = n - part.getCount();
			if (m <= 0) break;
			String name = id(s);
			s.decrement(m);
			inv.setStack(i, s.isEmpty() ? ItemStack.EMPTY : s);
			got.merge(name, m, Integer::sum);
			left -= m; moved += m;
		}
		inv.markDirty();
		if (moved == 0) return "Nichts genommen (leer, nicht vorhanden oder Inventar voll).";
		return "Genommen: " + got;
	}

	String put(BlockPos t, Item it, int count, boolean fuel) {
		Inventory inv = containerAt(t);
		if (inv == null) return "Kein Behälter bei " + pos(t);
		int[] slots;
		if (inv instanceof AbstractFurnaceBlockEntity) slots = new int[]{fuel ? 1 : 0};
		else slots = java.util.stream.IntStream.range(0, inv.size()).toArray();
		int left = count, moved = 0;
		for (ItemStack s : bot.getInventory().main) {
			if (left <= 0) break;
			if (!s.isOf(it)) continue;
			ItemStack part = s.copyWithCount(Math.min(left, s.getCount()));
			int n = insertInto(inv, part, slots);
			s.decrement(n);
			left -= n; moved += n;
			if (n == 0) break;
		}
		inv.markDirty();
		if (moved == 0) return "Nichts hineingelegt (nicht im Inventar oder Behälter voll/passt nicht).";
		return "Hineingelegt: " + moved + "x " + id(new ItemStack(it)) + "\n" + showContainer(t);
	}

	private static int insertInto(Inventory inv, ItemStack stack, int[] slots) {
		int start = stack.getCount();
		int max = Math.min(stack.getMaxCount(), inv.getMaxCountPerStack());
		for (int slot : slots) {
			if (stack.isEmpty()) break;
			ItemStack ex = inv.getStack(slot);
			if (!ex.isEmpty() && ItemStack.areItemsAndComponentsEqual(ex, stack) && ex.getCount() < max) {
				int k = Math.min(max - ex.getCount(), stack.getCount());
				ex.increment(k);
				stack.decrement(k);
				inv.setStack(slot, ex);
			}
		}
		for (int slot : slots) {
			if (stack.isEmpty()) break;
			if (inv.getStack(slot).isEmpty() && inv.isValid(slot, stack)) {
				int k = Math.min(max, stack.getCount());
				inv.setStack(slot, stack.copyWithCount(k));
				stack.decrement(k);
			}
		}
		return start - stack.getCount();
	}

	// ------------------------------------------------------------------ Handwerk

	String craft(Item it, int amount) {
		List<RecipeEntry<CraftingRecipe>> all = server.getRecipeManager().listAllOfType(RecipeType.CRAFTING);
		var reg = server.getRegistryManager();
		boolean table = nearestBlock(s -> s.isOf(Blocks.CRAFTING_TABLE), 4) != null;
		boolean anyRecipe = false, needTable = false;
		String missing = null;
		int made = 0;
		while (made < amount) {
			boolean progress = false;
			for (RecipeEntry<CraftingRecipe> e : all) {
				CraftingRecipe r = e.value();
				ItemStack out = r.getResult(reg);
				if (out.isEmpty() || !out.isOf(it)) continue;
				List<Ingredient> ings = r.getIngredients().stream().filter(i -> !i.isEmpty()).toList();
				if (ings.isEmpty()) continue;
				anyRecipe = true;
				if (!r.fits(2, 2) && !table) { needTable = true; continue; }
				List<Integer> use = match(ings);
				if (use == null) { missing = describe(ings); continue; }
				PlayerInventory inv = bot.getInventory();
				for (int slot : use) {
					ItemStack s = inv.main.get(slot);
					Item used = s.getItem();
					s.decrement(1);
					if (used.hasRecipeRemainder()) inv.offerOrDrop(new ItemStack(used.getRecipeRemainder()));
				}
				inv.offerOrDrop(out.copy());
				made += out.getCount();
				progress = true;
				break;
			}
			if (!progress) break;
		}
		String name = id(new ItemStack(it));
		if (made > 0) return "Hergestellt: " + made + "x " + name + (made < amount ? " (für mehr fehlen Zutaten)" : "") + ". Jetzt: " + invSummary(it);
		if (!anyRecipe) return "Kein Werkbank-Rezept für " + name + ". (Ofen? Siehe 'recipe " + name + "')";
		if (needTable && missing == null) return "Für " + name + " brauchst du eine Werkbank (crafting_table) in max. 4 Blöcken Entfernung.";
		return "Fehlende Zutaten für " + name + ". Benötigt: " + missing + (needTable ? " – außerdem eine Werkbank in der Nähe" : "");
	}

	private List<Integer> match(List<Ingredient> ings) {
		PlayerInventory inv = bot.getInventory();
		int[] left = new int[inv.main.size()];
		for (int i = 0; i < left.length; i++) left[i] = inv.main.get(i).getCount();
		List<Integer> use = new ArrayList<>();
		for (Ingredient ing : ings) {
			int found = -1;
			for (int i = 0; i < left.length; i++) {
				if (left[i] > 0 && ing.test(inv.main.get(i))) { found = i; break; }
			}
			if (found < 0) return null;
			left[found]--;
			use.add(found);
		}
		return use;
	}

	static String ingName(Ingredient ing) {
		ItemStack[] m = ing.getMatchingStacks();
		if (m.length == 0) return "?";
		LinkedHashSet<String> ids = new LinkedHashSet<>();
		for (ItemStack s : m) ids.add(id(s));
		List<String> l = new ArrayList<>(ids);
		return l.size() <= 3 ? String.join("/", l) : String.join("/", l.subList(0, 3)) + "/… (" + l.size() + " Varianten)";
	}

	static String describe(List<Ingredient> ings) {
		Map<String, Integer> c = new LinkedHashMap<>();
		for (Ingredient i : ings) c.merge(ingName(i), 1, Integer::sum);
		StringBuilder sb = new StringBuilder();
		for (var e : c.entrySet()) { if (!sb.isEmpty()) sb.append(", "); sb.append(e.getValue()).append("x ").append(e.getKey()); }
		return sb.toString();
	}

	String recipe(Item it) {
		var reg = server.getRegistryManager();
		String name = id(new ItemStack(it));
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (RecipeEntry<CraftingRecipe> e : server.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
			CraftingRecipe r = e.value();
			ItemStack out = r.getResult(reg);
			if (out.isEmpty() || !out.isOf(it)) continue;
			List<Ingredient> ings = r.getIngredients().stream().filter(i -> !i.isEmpty()).toList();
			if (ings.isEmpty()) continue;
			if (++n > 6) break;
			sb.append("Rezept ").append(n).append(" → ").append(out.getCount()).append("x ").append(name)
					.append(r.fits(2, 2) ? " (geht im Inventar)" : " (Werkbank nötig)").append(": ").append(describe(ings)).append('\n');
			if (r instanceof ShapedRecipe sr) {
				Map<String, Character> keys = new LinkedHashMap<>();
				List<Ingredient> all = sr.getIngredients();
				for (int y = 0; y < sr.getHeight(); y++) {
					sb.append("    ");
					for (int x = 0; x < sr.getWidth(); x++) {
						Ingredient ing = all.get(y * sr.getWidth() + x);
						if (ing.isEmpty()) { sb.append('.'); continue; }
						String k = ingName(ing);
						Character ch = keys.computeIfAbsent(k, kk -> (char) ('A' + keys.size()));
						sb.append(ch);
					}
					sb.append('\n');
				}
				sb.append("    ");
				keys.forEach((k, ch) -> sb.append(ch).append('=').append(k).append("  "));
				sb.append('\n');
			}
		}
		for (RecipeType<?> type : new RecipeType<?>[]{RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING}) {
			listCooking(type, it, reg, sb);
		}
		return sb.isEmpty() ? "Kein Rezept für " + name + " gefunden." : sb.toString();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void listCooking(RecipeType type, Item it, net.minecraft.registry.RegistryWrapper.WrapperLookup reg, StringBuilder sb) {
		List<RecipeEntry<?>> list = server.getRecipeManager().listAllOfType(type);
		String where = type == RecipeType.SMELTING ? "Ofen (furnace)" : type == RecipeType.BLASTING ? "Schmelzofen (blast_furnace)" : "Räucherofen (smoker)";
		int n = 0;
		for (RecipeEntry<?> e : list) {
			Recipe<?> r = e.value();
			if (!r.getResult(reg).isOf(it)) continue;
			if (++n > 3) break;
			sb.append(where).append(": ").append(describe(r.getIngredients())).append(" → ").append(id(r.getResult(reg))).append('\n');
		}
	}

	// ------------------------------------------------------------------ Aufgaben

	Object start(Task t) {
		cancelTask("abgebrochen (neue Aufgabe: " + t.name + ")");
		task = t;
		t.tick();
		if (t.done) task = null;
		return t;
	}

	public abstract class Task {
		final String name;
		final int startTick = server.getTicks();
		volatile boolean done;
		private boolean reportLater;
		private String result;

		Task(String name) { this.name = name; }

		abstract void tick();

		synchronized void complete(String msg) {
			if (done) return;
			done = true;
			result = msg;
			if (reportLater) events.add("Aufgabe '" + name + "' beendet: " + msg);
			notifyAll();
		}

		/** Wartet auf das Ergebnis; null = läuft noch (Ergebnis kommt dann als Ereignis). */
		public synchronized String await(long ms) {
			long end = System.currentTimeMillis() + ms;
			while (!done) {
				long left = end - System.currentTimeMillis();
				if (left <= 0) { reportLater = true; return null; }
				try { wait(left); } catch (InterruptedException e) { reportLater = true; return null; }
			}
			return result;
		}

		public String name() { return name; }
	}

	/** Läuft einen Weg ab (A*), plant bei Hindernissen neu. */
	class Navigator {
		BlockPos target;
		Predicate<BlockPos> goal;
		List<BlockPos> path;
		int idx, plans, stuck;
		Vec3d lastPos = Vec3d.ZERO;
		String error;

		void setGoal(BlockPos t, Predicate<BlockPos> g) {
			target = t.toImmutable(); goal = g; path = null; plans = 0; error = null;
		}

		/** 1 = angekommen, 0 = unterwegs, -1 = gescheitert (error). */
		int tick() {
			BlockPos cur = bot.getBlockPos();
			if (goal.test(cur)) { path = null; return 1; }
			if (path == null || idx >= path.size()) {
				int maxPlans = 6 + (int) (Math.sqrt(cur.getSquaredDistance(target)) / 12);
				if (++plans > maxPlans) { error = "Ziel " + pos(target) + " nicht erreichbar"; return -1; }
				Pathfinder.Result r = Pathfinder.find(world(), cur, goal, target, 9000);
				if (r.path.isEmpty()) { error = "kein Weg nach " + pos(target) + " gefunden"; return -1; }
				path = r.path; idx = 0; stuck = 0; lastPos = bot.getPos();
			}
			BlockPos wp = path.get(idx);
			if (!Pathfinder.standable(world(), wp)) { path = null; return 0; }
			if (step(wp)) idx++;
			Vec3d p = bot.getPos();
			if (p.squaredDistanceTo(lastPos) < 0.0004) {
				if (++stuck > 30) path = null;
			} else { stuck = 0; lastPos = p; }
			return 0;
		}

		private double standY(BlockPos wp) {
			ServerWorld w = world();
			var own = w.getBlockState(wp).getCollisionShape(w, wp);
			if (!own.isEmpty()) return wp.getY() + own.getMax(Direction.Axis.Y);
			var below = w.getBlockState(wp.down()).getCollisionShape(w, wp.down());
			if (!below.isEmpty()) return wp.getY() - 1 + Math.min(1.0, below.getMax(Direction.Axis.Y));
			return wp.getY();
		}

		/** Bewegt den Bot einen Tick Richtung Wegpunkt. true = erreicht. */
		private boolean step(BlockPos wp) {
			Vec3d p = bot.getPos();
			double tx = wp.getX() + 0.5, ty = standY(wp), tz = wp.getZ() + 0.5;
			double dx = tx - p.x, dz = tz - p.z, h = Math.sqrt(dx * dx + dz * dz), dy = ty - p.y;
			if (h < 0.15 && Math.abs(dy) < 0.3) { bot.setPosition(tx, ty, tz); return true; }
			double speed = bot.isTouchingWater() ? 0.12 : 0.21;
			double s = Math.min(h, speed);
			double nx = p.x, nz = p.z, ny = p.y;
			if (h > 1e-4) { nx += dx / h * s; nz += dz / h * s; }
			if (dy > 0.05) ny = Math.min(ty, p.y + 0.5);
			else if (dy < -0.05) { if (h < 0.5) ny = Math.max(ty, p.y - 0.6); }
			else ny = ty;
			if (h > 0.05) {
				float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
				bot.setYaw(yaw); bot.setHeadYaw(yaw); bot.bodyYaw = yaw; bot.setPitch(0);
			}
			bot.setPosition(nx, ny, nz);
			bot.setVelocity(Vec3d.ZERO);
			bot.fallDistance = 0;
			return false;
		}
	}

	class GotoTask extends Task {
		final Navigator nav = new Navigator();
		final PlayerEntity follow;
		final boolean forever;
		final double dist;
		int retarget, cooldown;

		GotoTask(String name, BlockPos t, double dist, PlayerEntity follow, boolean forever) {
			super(name);
			this.follow = follow; this.forever = forever; this.dist = dist;
			setTarget(t);
		}

		void setTarget(BlockPos t) {
			double d2 = dist * dist;
			nav.setGoal(t, p -> p.getSquaredDistance(t) <= d2);
		}

		@Override void tick() {
			if (follow != null) {
				if (follow.isRemoved() || follow.getWorld() != bot.getWorld()) {
					if (!forever) complete("Ziel ist weg (andere Dimension oder offline)");
					return;
				}
				if (--retarget <= 0) {
					retarget = 20;
					if (!follow.getBlockPos().equals(nav.target)) setTarget(follow.getBlockPos());
				}
			}
			if (cooldown > 0) { cooldown--; return; }
			int r = nav.tick();
			if (r == 1 && !forever) complete("Angekommen bei " + pos(bot.getBlockPos()));
			else if (r == -1) {
				if (forever) { cooldown = 40; setTarget(follow.getBlockPos()); }
				else complete("Fehlgeschlagen: " + nav.error + ". Stehe jetzt bei " + pos(bot.getBlockPos()));
			}
		}
	}

	class ReachTask extends Task {
		final Navigator nav = new Navigator();
		final BlockPos t;
		final boolean notInside;
		final java.util.function.Supplier<String> action;

		ReachTask(String name, BlockPos t, boolean notInside, java.util.function.Supplier<String> action) {
			super(name);
			this.t = t; this.notInside = notInside; this.action = action;
			nav.setGoal(t, p -> reachableFrom(p, t));
		}

		@Override void tick() {
			BlockPos b = bot.getBlockPos();
			boolean ok = inReach(t) && !(notInside && (b.equals(t) || b.up().equals(t)));
			if (!ok) {
				int r = nav.tick();
				if (r == -1) { complete("Komme nicht an " + pos(t) + " heran: " + nav.error); return; }
				if (r == 0) return;
			}
			complete(action.get());
		}
	}

	class MineTask extends Task {
		final Block block;
		final BlockPos fixed;
		final int want;
		final boolean force;
		final Navigator nav = new Navigator();
		final Set<Long> blacklist = new HashSet<>();
		BlockPos cur, last;
		int phase, mined, fails, breakTicks, collectTicks;
		float progress;
		String note = "";

		MineTask(Block block, BlockPos fixed, int want, boolean force) {
			super(fixed != null ? "mine " + pos(fixed) : "mine " + Registries.BLOCK.getId(block).getPath() + " x" + want);
			this.block = block; this.fixed = fixed; this.want = Math.max(1, want); this.force = force;
		}

		String summary(String why) {
			return why + ". Abgebaut: " + mined + (block != null ? "/" + want + " " + Registries.BLOCK.getId(block).getPath() : " Block") + note;
		}

		BlockPos findNearest() {
			ServerWorld w = world();
			BlockPos c = bot.getBlockPos();
			BlockPos best = null;
			double bd = Double.MAX_VALUE;
			int r = 32, ry = 16;
			for (BlockPos q : BlockPos.iterate(c.add(-r, -ry, -r), c.add(r, ry, r))) {
				if (!w.getBlockState(q).isOf(block) || blacklist.contains(q.asLong())) continue;
				double d = Math.sqrt(q.getSquaredDistance(c));
				boolean exposed = false;
				for (Direction dir : Direction.values()) if (Pathfinder.passable(w, q.offset(dir))) { exposed = true; break; }
				if (!exposed) d += 8;
				if (d < bd) { bd = d; best = q.toImmutable(); }
			}
			return best;
		}

		@Override void tick() {
			ServerWorld w = world();
			switch (phase) {
				case 0 -> {
					cur = fixed != null ? fixed : findNearest();
					if (cur == null) { complete(summary("Keine (weiteren) " + Registries.BLOCK.getId(block).getPath() + " in der Nähe gefunden")); return; }
					BlockState s = w.getBlockState(cur);
					if (s.isAir()) { complete("Bei " + pos(cur) + " ist nur Luft"); return; }
					if (!bot.isCreative() && s.isToolRequired()) {
						selectBestTool(s);
						if (!bot.canHarvest(s)) {
							if (!force && fixed == null) { complete("Für " + id(s) + " brauchst du ein passendes Werkzeug (sonst keine Drops). Mit 'mine!' trotzdem abbauen."); return; }
							note = " (ohne passendes Werkzeug – keine Drops)";
						}
					}
					final BlockPos t = cur;
					nav.setGoal(t, p -> reachableFrom(p, t));
					phase = 1;
				}
				case 1 -> {
					int r = inReach(cur) ? 1 : nav.tick();
					if (r == 1) { phase = 2; progress = 0; breakTicks = 0; }
					else if (r == -1) {
						if (fixed != null) { complete("Komme nicht an " + pos(cur) + " heran: " + nav.error); return; }
						blacklist.add(cur.asLong());
						if (++fails > 6) { complete(summary("Zu viele unerreichbare Blöcke")); return; }
						phase = 0;
					}
				}
				case 2 -> {
					BlockState s = w.getBlockState(cur);
					if (s.isAir() || (block != null && !s.isOf(block))) { w.setBlockBreakingInfo(bot.getId(), cur, -1); phase = 3; last = cur; collectTicks = 0; return; }
					if (!inReach(cur)) { phase = 1; return; }
					if (breakTicks == 0) selectBestTool(s);
					lookAt(Vec3d.ofCenter(cur));
					float delta = bot.isCreative() ? 1f : s.calcBlockBreakingDelta(bot, w, cur);
					if (delta <= 0) { complete("Block " + id(s) + " ist unzerstörbar"); return; }
					progress += delta;
					if (++breakTicks > 20 * 40) { w.setBlockBreakingInfo(bot.getId(), cur, -1); complete(summary("Abbau dauert zu lange – besseres Werkzeug nötig")); return; }
					if (breakTicks % 4 == 1) bot.swingHand(Hand.MAIN_HAND);
					w.setBlockBreakingInfo(bot.getId(), cur, Math.min(9, (int) (progress * 10)));
					if (progress >= 1f) {
						w.setBlockBreakingInfo(bot.getId(), cur, -1);
						if (bot.interactionManager.tryBreakBlock(cur)) mined++;
						else blacklist.add(cur.asLong());
						last = cur; phase = 3; collectTicks = 0;
					}
				}
				case 3 -> {
					ItemEntity item = null;
					double bd = Double.MAX_VALUE;
					for (ItemEntity ie : w.getEntitiesByClass(ItemEntity.class, new net.minecraft.util.math.Box(last).expand(4), Entity::isAlive)) {
						double d = ie.squaredDistanceTo(bot);
						if (d < bd) { bd = d; item = ie; }
					}
					if (item == null || ++collectTicks > 100) {
						if (mined >= want || fixed != null) { complete(summary("Fertig")); return; }
						phase = 0;
						return;
					}
					BlockPos ip = item.getBlockPos();
					if (!ip.equals(nav.target)) nav.setGoal(ip, p -> p.getSquaredDistance(ip) <= 1.5);
					if (nav.tick() == -1) collectTicks = 1000;
				}
				default -> complete("?");
			}
		}
	}

	class CollectTask extends Task {
		final Navigator nav = new Navigator();
		final int r;
		int ticks, got;

		CollectTask(int r) { super("collect"); this.r = r; }

		@Override void tick() {
			if (++ticks > 20 * 40) { complete("Zeit um. Inventar:\n" + Describe.inventory(bot)); return; }
			ItemEntity item = null;
			double bd = Double.MAX_VALUE;
			for (ItemEntity ie : world().getEntitiesByClass(ItemEntity.class, bot.getBoundingBox().expand(r), e -> e.isAlive() && !e.cannotPickup())) {
				double d = ie.squaredDistanceTo(bot);
				if (d < bd) { bd = d; item = ie; }
			}
			if (item == null) { complete("Keine Items mehr in " + r + " Blöcken.\n" + Describe.inventory(bot)); return; }
			BlockPos ip = item.getBlockPos();
			if (!ip.equals(nav.target)) nav.setGoal(ip, p -> p.getSquaredDistance(ip) <= 1.5);
			if (nav.tick() == -1) { complete("Komme an Item bei " + pos(ip) + " nicht heran.\n" + Describe.inventory(bot)); }
		}
	}

	class UseItemTask extends Task {
		final Predicate<ItemStack> pick;
		boolean started;
		int ticks;
		String what = "?";

		UseItemTask(String name, Predicate<ItemStack> pick) { super(name); this.pick = pick; }

		@Override void tick() {
			if (!started) {
				started = true;
				if (pick != null) {
					int slot = findSlot(pick);
					if (slot < 0) { complete("Nichts Passendes im Inventar"); return; }
					selectSlot(slot);
				}
				ItemStack st = bot.getMainHandStack();
				if (st.isEmpty()) { complete("Hand ist leer"); return; }
				what = id(st);
				ActionResult r = bot.interactionManager.interactItem(bot, world(), st, Hand.MAIN_HAND);
				if (!bot.isUsingItem()) {
					complete("Benutzt: " + what + " → " + r + (r.isAccepted() ? "" : " (geht gerade nicht – z.B. Hunger schon voll?)"));
				}
				return;
			}
			if (!bot.isUsingItem() || ++ticks > 100) {
				if (bot.isUsingItem()) bot.stopUsingItem();
				complete(String.format(Locale.ROOT, "Fertig mit %s. Hunger %d/20, Leben %.1f", what, bot.getHungerManager().getFoodLevel(), bot.getHealth()));
			}
		}
	}

	class AttackTask extends Task {
		final String what;
		final int want;
		final Navigator nav = new Navigator();
		LivingEntity target;
		int kills, ticks, retarget;

		AttackTask(String what, int want) { super("attack " + what); this.what = what.toLowerCase(Locale.ROOT); this.want = Math.max(1, want); }

		LivingEntity find() {
			if (what.startsWith("#")) {
				Entity e = world().getEntityById(Integer.parseInt(what.substring(1)));
				return e instanceof LivingEntity le && le.isAlive() && e != bot ? le : null;
			}
			LivingEntity best = null;
			double bd = Double.MAX_VALUE;
			for (LivingEntity e : world().getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(24), Entity::isAlive)) {
				if (e == bot || e instanceof PlayerEntity) continue;
				boolean match = what.equals("hostile") || what.equals("feind") ? e instanceof Monster : Describe.entityId(e).equals(what);
				if (!match) continue;
				double d = e.squaredDistanceTo(bot);
				if (d < bd) { bd = d; best = e; }
			}
			return best;
		}

		@Override void tick() {
			if (++ticks > 20 * 90) { complete("Zeit um. Besiegt: " + kills); return; }
			if (target == null || !target.isAlive() || target.isRemoved()) {
				if (target != null) {
					kills++;
					if (kills >= want || what.startsWith("#")) { complete("Besiegt: " + kills + "x " + Describe.entityId(target) + String.format(Locale.ROOT, ". Leben %.1f", bot.getHealth())); return; }
				}
				target = find();
				if (target == null) { complete(kills > 0 ? "Besiegt: " + kills + ", keine weiteren Ziele" : "Kein Ziel '" + what + "' in 24 Blöcken gefunden"); return; }
				selectWeapon();
				retarget = 0;
			}
			double d = bot.distanceTo(target);
			if (d <= 3.0) {
				lookAt(target.getEyePos());
				if (bot.getAttackCooldownProgress(0.5f) >= 0.95f) {
					bot.attack(target);
					bot.swingHand(Hand.MAIN_HAND);
				}
			} else {
				if (--retarget <= 0) {
					retarget = 10;
					BlockPos t = target.getBlockPos();
					nav.setGoal(t, p -> p.getSquaredDistance(t) <= 4);
				}
				if (nav.tick() == -1) complete("Komme nicht an " + Describe.entityId(target) + " heran: " + nav.error);
			}
		}
	}

	class GiveTask extends Task {
		final PlayerEntity p;
		final Item it;
		final int n;
		final Navigator nav = new Navigator();
		int retarget;

		GiveTask(PlayerEntity p, Item it, int n) { super("give " + p.getName().getString()); this.p = p; this.it = it; this.n = n; }

		@Override void tick() {
			if (findSlot(s -> s.isOf(it)) < 0) { complete("Nicht im Inventar: " + id(new ItemStack(it))); return; }
			if (p.isRemoved() || p.getWorld() != bot.getWorld()) { complete("Spieler nicht erreichbar"); return; }
			if (bot.distanceTo(p) > 2.5) {
				if (--retarget <= 0) { retarget = 20; BlockPos t = p.getBlockPos(); nav.setGoal(t, q -> q.getSquaredDistance(t) <= 4); }
				if (nav.tick() == -1) complete("Komme nicht zu " + p.getName().getString() + ": " + nav.error);
				return;
			}
			lookAt(p.getEyePos());
			complete(drop(it, n, 10).replace("Fallen gelassen", "Gegeben an " + p.getName().getString()));
		}
	}
}
