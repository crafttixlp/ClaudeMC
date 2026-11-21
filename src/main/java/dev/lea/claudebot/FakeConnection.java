package dev.lea.claudebot;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import org.jetbrains.annotations.Nullable;

/** Verbindung ohne echten Client: alle Pakete an den Bot werden verworfen. */
public class FakeConnection extends ClientConnection {
	public FakeConnection() {
		super(NetworkSide.SERVERBOUND);
	}

	@Override public void send(Packet<?> packet) {}
	@Override public void send(Packet<?> packet, @Nullable PacketCallbacks callbacks) {}
	@Override public void send(Packet<?> packet, @Nullable PacketCallbacks callbacks, boolean flush) {}
	@Override public void tryDisableAutoRead() {}
	@Override public void handleDisconnection() {}
	@Override public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T packetListener) {}
	@Override public void transitionOutbound(NetworkState<?> state) {}
}
