package dev.lea.claudebot;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

import java.util.*;
import java.util.function.Predicate;

/** Einfache A*-Wegsuche auf Blockebene (gehen, 1 Block hoch, bis 4 runter, schwimmen). */
public final class Pathfinder {
	private Pathfinder() {}

	public static final class Result {
		public final List<BlockPos> path;
		public final boolean complete;
		Result(List<BlockPos> path, boolean complete) { this.path = path; this.complete = complete; }
	}

	private static final class Node {
		final BlockPos pos; Node parent; double g; double f;
		Node(BlockPos pos) { this.pos = pos; }
	}

	public static boolean isWater(ServerWorld w, BlockPos p) {
		return w.getFluidState(p).isIn(FluidTags.WATER);
	}

	public static boolean passable(ServerWorld w, BlockPos p) {
		if (!w.isChunkLoaded(p)) return false;
		BlockState s = w.getBlockState(p);
		if (s.getFluidState().isIn(FluidTags.LAVA)) return false;
		if (s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.SWEET_BERRY_BUSH)
				|| s.isOf(Blocks.COBWEB) || s.isOf(Blocks.POWDER_SNOW) || s.isOf(Blocks.CACTUS)) return false;
		VoxelShape shape = s.getCollisionShape(w, p);
		return shape.isEmpty() || shape.getMax(Direction.Axis.Y) <= 0.2;
	}

	public static boolean solid(ServerWorld w, BlockPos p) {
		if (!w.isChunkLoaded(p)) return false;
		BlockState s = w.getBlockState(p);
		if (s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CACTUS) || s.getFluidState().isIn(FluidTags.LAVA)) return false;
		VoxelShape shape = s.getCollisionShape(w, p);
		return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) > 0.4;
	}

	/** Kann hier ein Spieler stehen (Füße bei p)? */
	public static boolean standable(ServerWorld w, BlockPos p) {
		if (!passable(w, p) || !passable(w, p.up())) return false;
		if (solid(w, p.down())) return true;
		// schwimmen an der Oberfläche
		return isWater(w, p) && !isWater(w, p.up());
	}

	public static Result find(ServerWorld w, BlockPos start, Predicate<BlockPos> goal, BlockPos target, int maxNodes) {
		Map<Long, Node> nodes = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
		Set<Long> closed = new HashSet<>();
		Node s = new Node(start.toImmutable());
		s.g = 0; s.f = h(start, target);
		nodes.put(start.asLong(), s);
		open.add(s);
		Node best = s; double bestH = h(start, target);
		int expanded = 0;
		while (!open.isEmpty() && expanded < maxNodes) {
			Node cur = open.poll();
			if (!closed.add(cur.pos.asLong())) continue;
			expanded++;
			if (goal.test(cur.pos)) return new Result(build(cur), true);
			double ch = h(cur.pos, target);
			if (ch < bestH) { bestH = ch; best = cur; }
			for (Move m : neighbours(w, cur.pos)) {
				long key = m.pos.asLong();
				if (closed.contains(key)) continue;
				double g = cur.g + m.cost;
				Node n = nodes.get(key);
				if (n == null) { n = new Node(m.pos); nodes.put(key, n); }
				else if (g >= n.g) continue;
				n.parent = cur; n.g = g; n.f = g + h(m.pos, target) * 1.2;
				open.add(n);
			}
		}
		if (best == s) return new Result(List.of(), false);
		return new Result(build(best), false);
	}

	private record Move(BlockPos pos, double cost) {}

	private static List<Move> neighbours(ServerWorld w, BlockPos n) {
		List<Move> out = new ArrayList<>(8);
		boolean inWater = isWater(w, n);
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos m = n.offset(d);
			if (standable(w, m)) { out.add(new Move(m, isWater(w, m) ? 2.0 : 1.0)); continue; }
			BlockPos up = m.up();
			if (standable(w, up) && passable(w, n.up(2))) { out.add(new Move(up, 1.6)); continue; }
			if (passable(w, m) && passable(w, m.up())) {
				for (int i = 1; i <= 4; i++) {
					BlockPos q = m.down(i);
					if (standable(w, q)) { out.add(new Move(q, 1.0 + i * 0.4)); break; }
					if (!passable(w, q)) break;
				}
			}
		}
		if (inWater) {
			if (standable(w, n.up()) || (isWater(w, n.up()) && passable(w, n.up(2)))) out.add(new Move(n.up(), 2.0));
		}
		return out;
	}

	private static double h(BlockPos a, BlockPos b) {
		return Math.sqrt(a.getSquaredDistance(b));
	}

	private static List<BlockPos> build(Node end) {
		LinkedList<BlockPos> list = new LinkedList<>();
		for (Node n = end; n.parent != null; n = n.parent) list.addFirst(n.pos);
		return new ArrayList<>(list);
	}
}
