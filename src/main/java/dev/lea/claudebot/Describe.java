package dev.lea.claudebot;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/** Wandelt die Spielwelt in Text um. */
public final class Describe {
	private Describe() {}

	public static String id(ItemStack s) {
		return Registries.ITEM.getId(s.getItem()).getPath();
	}

	public static String id(BlockState s) {
		return Registries.BLOCK.getId(s.getBlock()).getPath();
	}

	public static String entityId(Entity e) {
		return Registries.ENTITY_TYPE.getId(e.getType()).getPath();
	}

	public static String pos(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	public static String pos(Vec3d v) {
		return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
	}

	public static String stack(ItemStack s) {
		if (s.isEmpty()) return "nichts";
		String r = id(s) + " x" + s.getCount();
		if (s.isDamageable()) r += " (Haltbarkeit " + (s.getMaxDamage() - s.getDamage()) + "/" + s.getMaxDamage() + ")";
		return r;
	}

	public static String facing(float yaw) {
		float y = ((yaw % 360) + 360) % 360;
		if (y >= 45 && y < 135) return "Westen (-x)";
		if (y >= 135 && y < 225) return "Norden (-z)";
		if (y >= 225 && y < 315) return "Osten (+x)";
		return "Süden (+z)";
	}

	public static String state(BotController c) {
		BotPlayer b = c.bot;
		ServerWorld w = b.getServerWorld();
		StringBuilder sb = new StringBuilder();
		BlockPos bp = b.getBlockPos();
		long time = w.getTimeOfDay() % 24000;
		String biome = w.getBiome(bp).getKey().map(k -> k.getValue().getPath()).orElse("?");
		sb.append("== ").append(b.getName().getString()).append(" ==\n");
		sb.append("Position: ").append(pos(b.getPos())).append("  (Block ").append(pos(bp)).append("), Blick nach ")
				.append(facing(b.getYaw())).append('\n');
		sb.append("Dimension: ").append(w.getRegistryKey().getValue().getPath()).append(", Biom: ").append(biome).append('\n');
		sb.append("Zeit: ").append(time < 12500 || time > 23500 ? "Tag" : "Nacht").append(" (").append(time).append("/24000)")
				.append(", Wetter: ").append(w.isThundering() ? "Gewitter" : w.isRaining() ? "Regen" : "klar").append('\n');
		sb.append(String.format(Locale.ROOT, "Leben: %.1f/%.0f, Hunger: %d/20, Rüstung: %d, Level: %d, Modus: %s%n",
				b.getHealth(), b.getMaxHealth(), b.getHungerManager().getFoodLevel(), b.getArmor(), b.experienceLevel,
				b.interactionManager.getGameMode().getName()));
		sb.append("In der Hand: ").append(stack(b.getMainHandStack())).append('\n');
		sb.append("Steht auf: ").append(id(w.getBlockState(bp.down())));
		if (b.isSubmergedInWater()) sb.append(", UNTER WASSER");
		if (b.isOnFire()) sb.append(", BRENNT");
		sb.append('\n');
		sb.append("Aufgabe: ").append(c.taskStatus()).append('\n');
		String theme = BotRegistry.theme(c.botName);
		if (!theme.isEmpty()) sb.append("Dein Thema: ").append(theme).append('\n');
		sb.append(nearby(b, 16, 25));
		sb.append(inventory(b));
		return sb.toString();
	}

	public static String nearby(BotPlayer b, double r, int max) {
		ServerWorld w = b.getServerWorld();
		List<Entity> list = w.getOtherEntities(b, b.getBoundingBox().expand(r), Entity::isAlive);
		list.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(b)));
		StringBuilder players = new StringBuilder(), mobs = new StringBuilder(), items = new StringBuilder();
		int n = 0;
		for (Entity e : list) {
			if (n++ >= max) break;
			String d = String.format(Locale.ROOT, "%.1fm", e.distanceTo(b));
			if (e instanceof PlayerEntity p) {
				players.append("  ").append(p.getName().getString()).append(" bei ").append(pos(p.getPos())).append(", ").append(d)
						.append(String.format(Locale.ROOT, ", Leben %.0f", p.getHealth())).append(", hält ").append(stack(p.getMainHandStack())).append('\n');
			} else if (e instanceof ItemEntity ie) {
				items.append("  ").append(stack(ie.getStack())).append(" bei ").append(pos(ie.getPos())).append(", ").append(d).append('\n');
			} else if (e instanceof LivingEntity le) {
				mobs.append("  #").append(e.getId()).append(' ').append(entityId(e));
				if (e instanceof Monster) mobs.append(" (FEINDLICH)");
				if (e.hasCustomName()) mobs.append(" \"").append(e.getName().getString()).append('"');
				mobs.append(" bei ").append(pos(e.getPos())).append(", ").append(d)
						.append(String.format(Locale.ROOT, ", Leben %.0f/%.0f", le.getHealth(), le.getMaxHealth())).append('\n');
			} else {
				mobs.append("  #").append(e.getId()).append(' ').append(entityId(e)).append(" bei ").append(pos(e.getPos())).append(", ").append(d).append('\n');
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Spieler in der Nähe:\n").append(players.isEmpty() ? "  keine\n" : players);
		sb.append("Kreaturen/Objekte (").append((int) r).append(" Blöcke):\n").append(mobs.isEmpty() ? "  keine\n" : mobs);
		if (!items.isEmpty()) sb.append("Items am Boden:\n").append(items);
		return sb.toString();
	}

	public static String inventory(BotPlayer b) {
		PlayerInventory inv = b.getInventory();
		StringBuilder sb = new StringBuilder("Inventar (Slot 0-8 = Hotbar, * = ausgewählt):\n");
		boolean any = false;
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.main.get(i);
			if (s.isEmpty()) continue;
			any = true;
			sb.append("  [").append(i).append(i == inv.selectedSlot ? "*" : "").append("] ").append(stack(s)).append('\n');
		}
		if (!any) sb.append("  leer\n");
		StringBuilder arm = new StringBuilder();
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
			ItemStack s = b.getEquippedStack(slot);
			if (!s.isEmpty()) arm.append("  ").append(slot.getName()).append(": ").append(stack(s)).append('\n');
		}
		if (!arm.isEmpty()) sb.append("Ausrüstung:\n").append(arm);
		return sb.toString();
	}

	private static boolean boring(BlockState s) {
		return s.isAir();
	}

	/** Blocktypen im Umkreis mit Anzahl und nächster Position. */
	public static String scan(BotPlayer b, int r) {
		ServerWorld w = b.getServerWorld();
		BlockPos c = b.getBlockPos();
		Map<String, int[]> count = new HashMap<>();
		Map<String, BlockPos> nearest = new HashMap<>();
		Map<String, Double> nd = new HashMap<>();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
			m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
			if (!w.isChunkLoaded(m)) continue;
			BlockState s = w.getBlockState(m);
			if (boring(s)) continue;
			String id = id(s);
			count.computeIfAbsent(id, k -> new int[1])[0]++;
			double d = dx * dx + dy * dy + dz * dz;
			Double old = nd.get(id);
			if (old == null || d < old) { nd.put(id, d); nearest.put(id, m.toImmutable()); }
		}
		List<String> ids = new ArrayList<>(count.keySet());
		ids.sort(Comparator.comparingDouble(nd::get));
		StringBuilder sb = new StringBuilder("Blöcke im Umkreis " + r + " um " + pos(c) + " (nach Entfernung sortiert):\n");
		int n = 0;
		for (String id : ids) {
			if (n++ >= 60) { sb.append("  … (").append(ids.size() - 60).append(" weitere Typen)\n"); break; }
			sb.append(String.format(Locale.ROOT, "  %s: %d, nächster bei %s (%.1fm)%n", id, count.get(id)[0], pos(nearest.get(id)), Math.sqrt(nd.get(id))));
		}
		return sb.toString();
	}

	private static final String POOL = "ABCDEFGHIJKLMNOQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+=?/<>";

	private static char symbol(Map<String, Character> legend, String id) {
		Character ch = legend.get(id);
		if (ch != null) return ch;
		char c = legend.size() < POOL.length() ? POOL.charAt(legend.size()) : '?';
		legend.put(id, c);
		return c;
	}

	/** Horizontale Schnitte (Draufsicht) auf Fuß-, Kopf- und Bodenhöhe. */
	public static String map(BotPlayer b, int r, int[] layers) {
		ServerWorld w = b.getServerWorld();
		BlockPos c = b.getBlockPos();
		Map<String, Character> legend = new LinkedHashMap<>();
		Map<Long, Character> ents = entityMarks(b, r + 1, 3);
		StringBuilder sb = new StringBuilder();
		sb.append("Karte um ").append(pos(c)).append(" – Norden (-z) oben, Osten (+x) rechts. Spalte links = z, Kopfzeile = x-Versatz.\n");
		sb.append("Zeichen: . Luft, ~ Wasser, ! Lava, @ du, & Spieler, % feindlich, $ Tier/anderes, * Item\n");
		for (int dy : layers) {
			int y = c.getY() + dy;
			sb.append("\n-- Ebene y=").append(y).append(dy == 0 ? " (Füße)" : dy == 1 ? " (Kopf)" : dy == -1 ? " (Boden unter dir)" : "").append(" --\n");
			sb.append(header(r));
			for (int dz = -r; dz <= r; dz++) {
				int z = c.getZ() + dz;
				sb.append(String.format(Locale.ROOT, "%6d ", z));
				for (int dx = -r; dx <= r; dx++) {
					int x = c.getX() + dx;
					BlockPos p = new BlockPos(x, y, z);
					char ch;
					Character e = (dy == 0 || dy == 1) ? ents.get(BlockPos.asLong(x, c.getY(), z)) : null;
					if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) ch = '@';
					else if (e != null && dy == 0) ch = e;
					else ch = blockChar(w, p, legend);
					sb.append(ch);
				}
				sb.append('\n');
			}
		}
		sb.append(legend(legend));
		return sb.toString();
	}

	/** Draufsicht: oberster Block jeder Spalte + Höhe relativ zu dir. */
	public static String top(BotPlayer b, int r) {
		ServerWorld w = b.getServerWorld();
		BlockPos c = b.getBlockPos();
		Map<String, Character> legend = new LinkedHashMap<>();
		Map<Long, Character> ents = entityMarks(b, r + 1, 8);
		StringBuilder blocks = new StringBuilder(), heights = new StringBuilder();
		for (int dz = -r; dz <= r; dz++) {
			int z = c.getZ() + dz;
			blocks.append(String.format(Locale.ROOT, "%6d ", z));
			heights.append(String.format(Locale.ROOT, "%6d ", z));
			for (int dx = -r; dx <= r; dx++) {
				int x = c.getX() + dx;
				int top = Integer.MIN_VALUE;
				char ch = ' ';
				for (int y = c.getY() + 12; y >= c.getY() - 12; y--) {
					BlockPos p = new BlockPos(x, y, z);
					if (!w.isChunkLoaded(p)) break;
					BlockState s = w.getBlockState(p);
					if (s.isAir()) continue;
					top = y; ch = blockChar(w, p, legend); break;
				}
				Character e = ents.get(BlockPos.asLong(x, c.getY(), z));
				if (dx == 0 && dz == 0) ch = '@'; else if (e != null) ch = e;
				blocks.append(ch);
				heights.append(top == Integer.MIN_VALUE ? ' ' : heightChar(top + 1 - c.getY()));
			}
			blocks.append('\n'); heights.append('\n');
		}
		return "Draufsicht um " + pos(c) + " – Norden (-z) oben, Osten (+x) rechts.\n"
				+ "Zeichen: ~ Wasser, ! Lava, @ du, & Spieler, % feindlich, $ Tier/anderes, * Item\n\n-- oberster Block --\n"
				+ header(r) + blocks
				+ "\n-- Höhe der Oberfläche relativ zu deinen Füßen (0 = gleich, 1-9 höher, a-i = -1..-9, # = >9 höher, _ = >9 tiefer) --\n"
				+ header(r) + heights + legend(legend);
	}

	private static char heightChar(int d) {
		if (d == 0) return '0';
		if (d > 0) return d > 9 ? '#' : (char) ('0' + d);
		return d < -9 ? '_' : (char) ('a' - 1 - d);
	}

	private static String header(int r) {
		StringBuilder h1 = new StringBuilder("       ");
		for (int dx = -r; dx <= r; dx++) h1.append(dx == 0 ? '|' : (Math.abs(dx) % 5 == 0 ? '+' : ' '));
		return h1.append("  (| = deine x, + = alle 5 Blöcke)\n").toString();
	}

	private static String legend(Map<String, Character> legend) {
		if (legend.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("\nLegende: ");
		int i = 0;
		for (Map.Entry<String, Character> e : legend.entrySet()) {
			if (i++ > 0) sb.append(", ");
			sb.append(e.getValue()).append('=').append(e.getKey());
		}
		return sb.append('\n').toString();
	}

	private static char blockChar(ServerWorld w, BlockPos p, Map<String, Character> legend) {
		if (!w.isChunkLoaded(p)) return ' ';
		BlockState s = w.getBlockState(p);
		if (s.isAir()) return '.';
		if (s.getFluidState().isIn(FluidTags.LAVA)) return '!';
		if (s.getFluidState().isIn(FluidTags.WATER) && s.getCollisionShape(w, p).isEmpty() && s.isOf(net.minecraft.block.Blocks.WATER)) return '~';
		return symbol(legend, id(s));
	}

	private static Map<Long, Character> entityMarks(BotPlayer b, int r, int yRange) {
		Map<Long, Character> out = new HashMap<>();
		ServerWorld w = b.getServerWorld();
		int cy = b.getBlockY();
		for (Entity e : w.getOtherEntities(b, b.getBoundingBox().expand(r, yRange, r), Entity::isAlive)) {
			char ch = e instanceof PlayerEntity ? '&' : e instanceof ItemEntity ? '*' : e instanceof Monster ? '%' : '$';
			long k = BlockPos.asLong(e.getBlockX(), cy, e.getBlockZ());
			Character old = out.get(k);
			if (old == null || ch == '&' || (ch == '%' && old != '&')) out.put(k, ch);
		}
		return out;
	}
}
