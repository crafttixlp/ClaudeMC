package dev.lea.claudebot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dev.lea.claudebot.Describe.entityId;
import static dev.lea.claudebot.Describe.id;
import static dev.lea.claudebot.Describe.pos;
import static dev.lea.claudebot.Describe.stack;

/**
 * Echtes Sichtfeld: Sichtstrahlen aus Augenhöhe. Was kein Strahl trifft, sieht der Bot nicht
 * (keine Blöcke hinter Wänden, Dunkelheit verbirgt). Gesehenes merkt er sich für spätere Suchen.
 */
public final class Vision {
	static final int COLS = 64, ROWS = 36;
	static final double HFOV = 100, VFOV = 62, RANGE = 48, HEAR = 16;
	private static final int MAX_SEEN_PER_BLOCK = 2000;

	private final BotController c;
	/** Gesehene Blockpositionen je Blocktyp (Gedächtnis für "mine <block>"). */
	private final Map<Block, LinkedHashSet<Long>> seen = new HashMap<>();

	Vision(BotController c) { this.c = c; }

	private static final class Info {
		int count;
		BlockPos nearest;
		double dist = Double.MAX_VALUE;
	}

	/** Ergebnis eines Blicks. */
	private static final class Look {
		final Map<String, Info> blocks = new HashMap<>();
		final Set<Long> hit = new HashSet<>();
		final List<BlockPos> signs = new ArrayList<>();
		int sky, dark, rays;
	}

	private static boolean seeThrough(BlockState s) {
		return s.isIn(BlockTags.IMPERMEABLE);
	}

	/** Wirft das Strahlenraster für eine Blickrichtung. */
	private void cast(Look look, float yawDeg, float pitchDeg) {
		BotPlayer b = c.bot;
		ServerWorld w = b.getServerWorld();
		Vec3d eye = b.getEyePos();
		double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
		Vec3d f = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3d r = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3d u = r.crossProduct(f);
		double th = Math.tan(Math.toRadians(HFOV / 2)), tv = Math.tan(Math.toRadians(VFOV / 2));
		for (int j = 0; j < ROWS; j++) {
			double ay = (1 - 2 * (j + 0.5) / ROWS) * tv;
			for (int i = 0; i < COLS; i++) {
				double ax = (2 * (i + 0.5) / COLS - 1) * th;
				Vec3d d = f.add(r.multiply(ax)).add(u.multiply(ay)).normalize();
				look.rays++;
				ray(look, w, eye, d);
			}
		}
	}

	private void ray(Look look, ServerWorld w, Vec3d eye, Vec3d d) {
		BotPlayer b = c.bot;
		Vec3d start = eye;
		double left = RANGE;
		boolean inWater = false;
		for (int pass = 0; pass < 4; pass++) {
			Vec3d end = start.add(d.multiply(left));
			BlockHitResult h = w.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE,
					inWater ? RaycastContext.FluidHandling.NONE : RaycastContext.FluidHandling.ANY, b));
			if (h.getType() == HitResult.Type.MISS) { if (!inWater) look.sky++; return; }
			BlockPos p = h.getBlockPos();
			BlockState s = w.getBlockState(p);
			double dist = Math.sqrt(p.getSquaredDistance(eye));
			boolean water = s.getBlock() == Blocks.WATER;
			// Dunkelheit: ohne Licht erkennt man weiter weg nichts.
			if (s.getLuminance() == 0 && dist > 8 && w.getLightLevel(p.offset(h.getSide())) < 4) { look.dark++; return; }
			note(look, s, p.toImmutable(), dist);
			if (!(water || seeThrough(s))) return;
			// Durch Wasser und Glas weiterschauen, aber nicht weit.
			if (water) inWater = true;
			left = Math.min(left - h.getPos().distanceTo(start), water ? 10 : 24);
			Vec3d q = h.getPos();
			for (int k = 0; k < 4 && BlockPos.ofFloored(q).equals(p); k++) q = q.add(d.multiply(0.5));
			if (water) {
				// Bis zum Ende der Wassersäule in Blickrichtung springen (höchstens ein paar Schritte).
				for (int k = 0; k < 20 && w.getBlockState(BlockPos.ofFloored(q)).getBlock() == Blocks.WATER; k++) {
					q = q.add(d.multiply(0.5));
					left -= 0.5;
				}
			}
			if (left <= 0.5) return;
			start = q;
		}
	}

	private void note(Look look, BlockState s, BlockPos p, double dist) {
		if (!look.hit.add(p.asLong())) return;
		Info in = look.blocks.computeIfAbsent(id(s), k -> new Info());
		in.count++;
		if (dist < in.dist) { in.dist = dist; in.nearest = p; }
		LinkedHashSet<Long> set = seen.computeIfAbsent(s.getBlock(), k -> new LinkedHashSet<>());
		set.add(p.asLong());
		if (set.size() > MAX_SEEN_PER_BLOCK) set.remove(set.iterator().next());
		if (s.isIn(BlockTags.ALL_SIGNS) && dist <= 16) look.signs.add(p);
	}

	/** Richtung relativ zum Blick: vorne, links, rechts, hinten (plus oben/unten). */
	private String dir(Vec3d target) {
		BotPlayer b = c.bot;
		Vec3d e = b.getEyePos();
		double dx = target.x - e.x, dy = target.y - e.y, dz = target.z - e.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		double rel = ((Math.toDegrees(Math.atan2(-dx, dz)) - b.getYaw()) % 360 + 540) % 360 - 180;
		String s = Math.abs(rel) <= 25 ? "vorne" : Math.abs(rel) >= 135 ? "hinten"
				: (Math.abs(rel) <= 65 ? "vorne-" : "") + (rel < 0 ? "links" : "rechts");
		if (h < 3 * Math.abs(dy)) s = dy > 0 ? "oben" : "unten";
		else if (Math.abs(dy) > 0.5 * h && Math.abs(dy) > 2) s += dy > 0 ? " oben" : " unten";
		return s;
	}

	private boolean inFov(Entity e) {
		Vec3d to = e.getEyePos().subtract(c.bot.getEyePos()).normalize();
		return to.dotProduct(c.bot.getRotationVec(1f)) >= Math.cos(Math.toRadians(HFOV / 2 + 5));
	}

	/** Wesen mit Sichtlinie; dazu Geräusche von Feinden, die man nicht sieht. */
	String entities(boolean around, double range, int max) {
		BotPlayer b = c.bot;
		ServerWorld w = b.getServerWorld();
		List<Entity> list = w.getOtherEntities(b, b.getBoundingBox().expand(range), Entity::isAlive);
		list.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(b)));
		StringBuilder players = new StringBuilder(), mobs = new StringBuilder(), items = new StringBuilder(), heard = new StringBuilder();
		int n = 0;
		for (Entity e : list) {
			double dist = e.distanceTo(b);
			if (dist > range) continue;
			boolean visible = (around || inFov(e)) && b.canSee(e)
					&& (dist <= 8 || e.isGlowing() || w.getLightLevel(e.getBlockPos()) >= 4);
			String where = String.format(Locale.ROOT, "%s, %.0fm %s", pos(e.getPos()), dist, dir(e.getEyePos()));
			if (!visible) {
				// Hören: Spieler und Monster in der Nähe, ohne genaue Position.
				if (dist <= HEAR && (e instanceof Monster || e instanceof PlayerEntity) && heard.length() < 200)
					heard.append("  ").append(e instanceof PlayerEntity ? "Schritte" : entityId(e))
							.append(String.format(Locale.ROOT, " ~%.0fm %s%n", Math.max(2, Math.round(dist / 4) * 4.0), dir(e.getEyePos())));
				continue;
			}
			if (n++ >= max) break;
			if (e instanceof PlayerEntity p) {
				players.append("  ").append(p.getName().getString()).append(" bei ").append(where)
						.append(", hält ").append(stack(p.getMainHandStack()));
				if (p.hurtTime > 0) players.append(", wird getroffen");
				if (p.isSneaking()) players.append(", schleicht");
				players.append('\n');
			} else if (e instanceof ItemEntity ie) {
				items.append("  ").append(stack(ie.getStack())).append(" bei ").append(where).append('\n');
			} else {
				mobs.append("  #").append(e.getId()).append(' ').append(entityId(e));
				if (e instanceof Monster) mobs.append(" (FEINDLICH)");
				if (e.hasCustomName()) mobs.append(" \"").append(e.getName().getString()).append('"');
				mobs.append(" bei ").append(where);
				if (e instanceof LivingEntity le && le.hurtTime > 0) mobs.append(", verletzt");
				if (e.isOnFire()) mobs.append(", brennt");
				mobs.append('\n');
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Spieler in Sicht:\n").append(players.isEmpty() ? "  keine\n" : players);
		sb.append("Kreaturen in Sicht:\n").append(mobs.isEmpty() ? "  keine\n" : mobs);
		if (!items.isEmpty()) sb.append("Items am Boden:\n").append(items);
		if (!heard.isEmpty()) sb.append("Du hörst (nicht in Sicht):\n").append(heard);
		return sb.toString();
	}

	private String crosshair() {
		BotPlayer b = c.bot;
		HitResult h = b.raycast(4.5, 1f, false);
		if (!(h instanceof BlockHitResult bh) || h.getType() == HitResult.Type.MISS) return "Fadenkreuz: nichts in Reichweite\n";
		BlockPos p = bh.getBlockPos();
		return String.format(Locale.ROOT, "Fadenkreuz: %s bei %s (Seite %s, %.1fm)%n", id(b.getServerWorld().getBlockState(p)), pos(p),
				bh.getSide().getName(), bh.getPos().distanceTo(b.getEyePos()));
	}

	/** Blick nach vorne, oder Rundumblick (4 Richtungen). Füllt das Gedächtnis. */
	String see(boolean around) {
		BotPlayer b = c.bot;
		Look look = scanOnly(around);
		StringBuilder sb = new StringBuilder();
		sb.append(around ? "Rundumblick" : "Blick nach " + Describe.facing(b.getYaw())).append(" von ").append(pos(b.getBlockPos()))
				.append(String.format(Locale.ROOT, " (Sichtweite %.0f, nur was du wirklich siehst):%n", RANGE));
		if (!around) sb.append(crosshair());
		sb.append(entities(around, RANGE, 30));
		List<Map.Entry<String, Info>> l = new ArrayList<>(look.blocks.entrySet());
		l.sort(Comparator.comparingDouble(e -> e.getValue().dist));
		sb.append("Blöcke (Typ: Anzahl sichtbar, nächster):\n");
		int n = 0;
		for (Map.Entry<String, Info> e : l) {
			if (n++ >= 45) { sb.append("  … (").append(l.size() - 45).append(" weitere Typen)\n"); break; }
			Info in = e.getValue();
			sb.append(String.format(Locale.ROOT, "  %s: %d, %s (%.0fm %s)%n", e.getKey(), in.count, pos(in.nearest), in.dist, dir(Vec3d.ofCenter(in.nearest))));
		}
		if (l.isEmpty()) sb.append("  keine\n");
		for (BlockPos p : look.signs) {
			if (b.getServerWorld().getBlockEntity(p) instanceof SignBlockEntity sign)
				sb.append("Schild bei ").append(pos(p)).append(": ").append(BotController.signText(sign)).append('\n');
		}
		int pct = look.rays == 0 ? 0 : 100 * look.sky / look.rays, dk = look.rays == 0 ? 0 : 100 * look.dark / look.rays;
		sb.append("Freier Himmel/Ferne: ").append(pct).append(" %");
		if (dk > 0) sb.append(", zu dunkel zum Erkennen: ").append(dk).append(" % (Fackel hilft)");
		sb.append('\n');
		return sb.toString();
	}

	private Look scanOnly(boolean around) {
		BotPlayer b = c.bot;
		Look look = new Look();
		if (around) {
			float pitch = Math.max(-15, Math.min(25, b.getPitch()));
			for (int k = 0; k < 4; k++) cast(look, b.getYaw() + 90 * k, pitch);
			cast(look, b.getYaw(), 70); // einmal vor die Füße
		} else {
			cast(look, b.getYaw(), b.getPitch());
		}
		return look;
	}

	/** Schaut sich um, ohne Text zu erzeugen (für Suchen). */
	void lookAround() { scanOnly(true); }

	/** Gemerkte Positionen dieses Blocktyps, die noch stimmen. Veraltete werden vergessen. */
	List<BlockPos> remembered(Block block) {
		List<BlockPos> out = new ArrayList<>();
		LinkedHashSet<Long> set = seen.get(block);
		if (set == null) return out;
		ServerWorld w = c.bot.getServerWorld();
		set.removeIf(l -> {
			BlockPos p = BlockPos.fromLong(l);
			if (!w.isChunkLoaded(p)) return false;
			if (!w.getBlockState(p).isOf(block)) return true;
			out.add(p);
			return false;
		});
		return out;
	}

	void forgetAll() { seen.clear(); }
}
