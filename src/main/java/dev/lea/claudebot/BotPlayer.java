package dev.lea.claudebot;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/** Der Spieler "Claude" – ein serverseitiger Spieler ohne Client. */
public class BotPlayer extends ServerPlayerEntity {
	public BotPlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
		super(server, world, profile, SyncedClientOptions.createDefault());
	}

	@Override
	public void tick() {
		try {
			super.tick();
			// Normalerweise ruft die Netzwerkverbindung das auf – die gibt es hier nicht.
			this.playerTick();
		} catch (NullPointerException ignored) {
		}
		if (this.server.getTicks() % 10 == 0) {
			this.networkHandler.syncWithPlayerPosition();
			this.getServerWorld().getChunkManager().updatePosition(this);
		}
	}

	@Override
	public String getIp() {
		return "127.0.0.1";
	}

	@Override
	public void onDeath(DamageSource source) {
		String msg = this.getDamageTracker().getDeathMessage().getString();
		super.onDeath(source);
		// Werte zurücksetzen, damit der gespeicherte Spielstand nicht "tot" ist.
		this.setHealth(20.0f);
		this.hungerManager = new HungerManager();
		this.extinguish();
		this.clearStatusEffects();
		BotController c = BotController.get();
		if (c != null) c.onBotDied(this, msg);
	}
}
