package dev.lea.claudebot;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Bildmodus: malt aus Sichtstrahlen ein Bild aus Sicht des Bots (Blockfarben, Schatten, Nebel, Wesen mit Nummer, HUD).
 * Kein echter Minecraft-Renderer, aber dieselbe Perspektive und dieselben Regeln wie {@link Vision}.
 */
public final class Painter {
	static final int W = 320, H = 180, SCALE = 2;
	static final double HFOV = 100, RANGE = 64;

	/** Ergebnis: PNG und Legende zu den Nummern im Bild. */
	public record Picture(byte[] png, String legend) {}

	private Painter() {}

	private static int mix(int a, int b, double t) {
		t = Math.max(0, Math.min(1, t));
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static int scale(int c, double f) {
		return mix(0, c, f);
	}

	public static Picture paint(BotController c) throws Exception {
		BotPlayer b = c.bot;
		ServerWorld w = b.getServerWorld();
		Vec3d eye = b.getEyePos();
		double yaw = Math.toRadians(b.getYaw()), pitch = Math.toRadians(b.getPitch());
		Vec3d f = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3d r = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3d u = r.crossProduct(f);
		double th = Math.tan(Math.toRadians(HFOV / 2)), tv = th * H / W;

		boolean dayTime = w.getAmbientDarkness() < 4;
		int skyTop = dayTime ? 0x6FA8FF : 0x05070F, skyLow = dayTime ? 0xBFD9FF : 0x141A2E;
		if (w.isRaining() && dayTime) { skyTop = 0x7A8794; skyLow = 0xA9B2BA; }

		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		for (int j = 0; j < H; j++) {
			double ay = (1 - 2 * (j + 0.5) / H) * tv;
			for (int i = 0; i < W; i++) {
				double ax = (2 * (i + 0.5) / W - 1) * th;
				Vec3d d = f.add(r.multiply(ax)).add(u.multiply(ay)).normalize();
				int sky = mix(skyLow, skyTop, Math.max(0, d.y) * 1.5);
				BlockHitResult h = w.raycast(new RaycastContext(eye, eye.add(d.multiply(RANGE)), RaycastContext.ShapeType.OUTLINE,
						RaycastContext.FluidHandling.ANY, b));
				if (h.getType() == HitResult.Type.MISS) { img.setRGB(i, j, sky); continue; }
				BlockPos p = h.getBlockPos();
				BlockState s = w.getBlockState(p);
				int col = s.getMapColor(w, p).color;
				if (col == 0) col = 0xCFE4EC; // Glas, Fackeln und Ähnliches haben keine Kartenfarbe
				Direction side = h.getSide();
				double shade = switch (side) { case UP -> 1.0; case DOWN -> 0.5; case NORTH, SOUTH -> 0.8; default -> 0.65; };
				int light = Math.max(s.getLuminance(), w.getLightLevel(p.offset(side)));
				shade *= 0.12 + 0.88 * light / 15.0;
				// Blockkanten leicht abdunkeln, damit einzelne Blöcke erkennbar bleiben.
				Vec3d hp = h.getPos();
				double fx = hp.x - Math.floor(hp.x), fy = hp.y - Math.floor(hp.y), fz = hp.z - Math.floor(hp.z);
				double e1, e2;
				if (side.getAxis() == Direction.Axis.Y) { e1 = fx; e2 = fz; } else if (side.getAxis() == Direction.Axis.X) { e1 = fy; e2 = fz; } else { e1 = fx; e2 = fy; }
				double dist = hp.distanceTo(eye);
				double edge = 0.03 + dist * 0.004;
				if (dist < 24 && (e1 < edge || e1 > 1 - edge || e2 < edge || e2 > 1 - edge)) shade *= 0.72;
				col = scale(col, shade);
				col = mix(col, sky, (dist - RANGE * 0.5) / (RANGE * 0.5));
				img.setRGB(i, j, col);
			}
		}

		// Wesen in Sicht: Rechteck in Körpergröße mit Nummer.
		List<Entity> list = new ArrayList<>(w.getOtherEntities(b, b.getBoundingBox().expand(RANGE), Entity::isAlive));
		list.sort(Comparator.comparingDouble(e -> -e.squaredDistanceTo(b)));
		BufferedImage out = new BufferedImage(W * SCALE, H * SCALE, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(img, 0, 0, W * SCALE, H * SCALE, null);
		boolean text = true;
		try { g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13)); g.getFontMetrics(); } catch (Throwable t) { text = false; }
		List<String> legend = new ArrayList<>();
		int nr = 0;
		for (Entity e : list) {
			Vec3d v = e.getPos().add(0, e.getHeight() / 2, 0).subtract(eye);
			double z = v.dotProduct(f);
			if (z < 0.3) continue;
			double sx = (v.dotProduct(r) / z / th * 0.5 + 0.5) * W * SCALE, sy = (0.5 - v.dotProduct(u) / z / tv * 0.5) * H * SCALE;
			if (sx < 0 || sx >= W * SCALE || sy < 0 || sy >= H * SCALE || !b.canSee(e)) continue;
			double dist = e.distanceTo(b);
			if (dist > 8 && !e.isGlowing() && w.getLightLevel(e.getBlockPos()) < 4) continue;
			int hh = (int) Math.max(4, e.getHeight() / z / tv * 0.5 * H * SCALE), ww = (int) Math.max(4, e.getWidth() / z / th * 0.5 * W * SCALE);
			Color col = e instanceof Monster ? new Color(0xD03030) : e instanceof PlayerEntity ? new Color(0x3F7FE0)
					: e instanceof ItemEntity ? new Color(0x30C0C0) : new Color(0xC89040);
			int x0 = (int) (sx - ww / 2.0), y0 = (int) (sy - hh / 2.0);
			g.setColor(col);
			g.fillRect(x0, y0, ww, hh);
			g.setColor(Color.BLACK);
			g.drawRect(x0, y0, ww, hh);
			nr++;
			if (text) {
				String label = String.valueOf(nr);
				g.setColor(Color.BLACK);
				g.fillRect(x0, y0 - 15, 8 * label.length() + 6, 15);
				g.setColor(Color.WHITE);
				g.drawString(label, x0 + 3, y0 - 3);
			}
			String what = e instanceof PlayerEntity pl ? "Spieler " + pl.getName().getString()
					: e instanceof ItemEntity ie ? "Item " + Describe.stack(ie.getStack())
					: Describe.entityId(e) + " #" + e.getId() + (e instanceof Monster ? " FEINDLICH" : "");
			legend.add(String.format(Locale.ROOT, "%d = %s bei %s, %.0fm", nr, what, Describe.pos(e.getPos()), dist));
		}

		// HUD: Fadenkreuz, Leben (rot) und Hunger (braun) wie am Bildschirm.
		int cx = W * SCALE / 2, cy = H * SCALE / 2;
		g.setColor(Color.WHITE);
		g.fillRect(cx - 7, cy - 1, 15, 2);
		g.fillRect(cx - 1, cy - 7, 2, 15);
		int barW = 162, by = H * SCALE - 16;
		g.setColor(new Color(0x202020));
		g.fillRect(cx - barW - 6, by - 2, barW + 4, 12);
		g.fillRect(cx + 2, by - 2, barW + 4, 12);
		g.setColor(new Color(0xE02020));
		g.fillRect(cx - barW - 4, by, (int) (barW * Math.min(1, b.getHealth() / b.getMaxHealth())), 8);
		g.setColor(new Color(0xB07028));
		g.fillRect(cx + 4, by, barW * b.getHungerManager().getFoodLevel() / 20, 8);
		g.dispose();

		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(out, "png", bos);
		StringBuilder sb = new StringBuilder();
		sb.append("Bild aus deiner Sicht, Blick nach ").append(Describe.facing(b.getYaw())).append(" von ").append(Describe.pos(b.getBlockPos()))
				.append(". Farbige Rechtecke sind Wesen: rot = feindlich, blau = Spieler, braun = Tier, türkis = Item. Unten: Leben (rot), Hunger (braun).\n");
		for (String l : legend) sb.append("  ").append(l).append('\n');
		if (!text && nr > 0) sb.append("  (Nummern konnten nicht ins Bild geschrieben werden; Reihenfolge: von fern nach nah)\n");
		return new Picture(bos.toByteArray(), sb.toString());
	}
}
