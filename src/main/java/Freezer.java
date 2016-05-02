import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.comphenix.protocol.wrappers.EnumWrappers;

public class Freezer extends PacketAdapter {
	private Set<UUID> frozenPlayers = new HashSet<>();
	private ProtocolManager protocolManager;

	public Freezer(ProtocolManager manager, Plugin plugin, ListenerPriority priority) {
		super(plugin, priority, PacketType.Play.Client.POSITION,
				PacketType.Play.Client.POSITION_LOOK);
		this.protocolManager = manager;
	}

	@Override
	public void onPacketReceiving(PacketEvent event) {
		if (!isFrozen(event.getPlayer())) {
			// Only process frozen players
			return;
		}

		if (event.getPacketType() == PacketType.Play.Client.POSITION) {
			// Convert to a "Player" packet, which has no real data.
			event.setCancelled(true);

			PacketContainer oldPacket = event.getPacket();
			PacketContainer newPacket = protocolManager.createPacket(PacketType.Play.Client.FLYING);

			newPacket.getBooleans().write(0, oldPacket.getBooleans().read(0));  // On ground

			try {
				// Can't set the event's packet directly due to https://github.com/dmulloy2/ProtocolLib/issues/201
				protocolManager.recieveClientPacket(event.getPlayer(), newPacket);
			} catch (Exception e) {
				throw new RuntimeException("Failed to rebroadcast player packet", e);
			}

			resendPosition(event.getPlayer());
		} else if (event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
			// We want to cancel out the position portion but not the
			// look portion, so we resend the packet as a look packet
			event.setCancelled(true);

			PacketContainer oldPacket = event.getPacket();
			PacketContainer newPacket = protocolManager.createPacket(PacketType.Play.Client.LOOK);

			// Copy over fields to the new packet
			newPacket.getFloat().write(0, oldPacket.getFloat().read(0));  // Yaw
			newPacket.getFloat().write(1, oldPacket.getFloat().read(1));  // Pitch
			newPacket.getBooleans().write(0, oldPacket.getBooleans().read(0));  // On ground

			try {
				protocolManager.recieveClientPacket(event.getPlayer(), newPacket);
			} catch (Exception e) {
				throw new RuntimeException("Failed to rebroadcast look packet", e);
			}

			resendPosition(event.getPlayer());
		}
	}

	public boolean isFrozen(Player player) {
		return frozenPlayers.contains(player.getUniqueId());
	}

	public void freezePlayer(Player player) {
		if (frozenPlayers.add(player.getUniqueId())) {
			startFreeze(player);
		}
	}

	public void unfreezePlayer(Player player) {
		if (frozenPlayers.remove(player.getUniqueId())) {
			endFreeze(player);
		}
	}

	private void startFreeze(Player player) {
		PacketContainer abilities = protocolManager.createPacket(PacketType.Play.Server.ABILITIES);
		abilities.getBooleans().write(0, false);  // Is invincible - currently unhandled
		abilities.getBooleans().write(1, true);  // Is flying
		abilities.getBooleans().write(2, false);  // Allow flight is set to false so that players cannot toggle flight
		abilities.getBooleans().write(3, player.getGameMode() == GameMode.CREATIVE);
		abilities.getFloat().write(0, 0f);  // Set fly speed to 0
		// Since we are flying, you'd think walk speed doesn't matter, but this is actually FOV.
		// The division by two is needed because bukkit decided to multiply the value by two for some strange reason.
		abilities.getFloat().write(1, player.getWalkSpeed() / 2);

		try {
			protocolManager.sendServerPacket(player, abilities);
		} catch (Exception e) {
			throw new RuntimeException("Failed to send freeze start packet", e);
		}

		resendPosition(player);
	}

	private void endFreeze(Player player) {
		PacketContainer abilities = protocolManager.createPacket(PacketType.Play.Server.ABILITIES);

		abilities.getBooleans().write(0, false);  // Is invincible - currently unhandled
		abilities.getBooleans().write(1, player.isFlying());
		abilities.getBooleans().write(2, player.getAllowFlight());
		abilities.getBooleans().write(3, player.getGameMode() == GameMode.CREATIVE);
		abilities.getFloat().write(0, player.getFlySpeed() / 2);  // Division by two is needed to get the real value
		abilities.getFloat().write(1, player.getWalkSpeed() / 2);

		try {
			protocolManager.sendServerPacket(player, abilities);
		} catch (Exception e) {
			throw new RuntimeException("Failed to send freeze end packet", e);
		}
	}

	private void resendPosition(Player player) {
		PacketContainer positionPacket = protocolManager.createPacket(PacketType.Play.Server.POSITION);
		Location location = player.getLocation();

		positionPacket.getDoubles().write(0, location.getX());
		positionPacket.getDoubles().write(1, location.getY());
		positionPacket.getDoubles().write(2, location.getZ());
		positionPacket.getFloat().write(0, 0f);  // No change in yaw
		positionPacket.getFloat().write(1, 0f);  // No change in pitch
		getFlagsModifier(positionPacket).write(0, EnumSet.of(PlayerTeleportFlag.X_ROT, PlayerTeleportFlag.Y_ROT));  // Mark pitch and yaw as relative

		PacketContainer velocityPacket = protocolManager.createPacket(PacketType.Play.Server.ENTITY_VELOCITY);
		velocityPacket.getIntegers().write(0, player.getEntityId());
		velocityPacket.getIntegers().write(1, 0).write(2, 0).write(3, 0);  // Set velocity to 0,0,0

		try {
			protocolManager.sendServerPacket(player, positionPacket);
			protocolManager.sendServerPacket(player, velocityPacket);
		} catch (Exception e) {
			throw new RuntimeException("Failed to send position and velocity packets", e);
		}
	}

	private static final Class<?> FLAGS_CLASS = MinecraftReflection.getMinecraftClass("EnumPlayerTeleportFlags",
			"PacketPlayOutPosition$EnumPlayerTeleportFlags");

	private static enum PlayerTeleportFlag {
		X, Y, Z, Y_ROT, X_ROT
	}

	private StructureModifier<Set<PlayerTeleportFlag>> getFlagsModifier(PacketContainer packet) {
		return packet.getModifier().withType(Set.class,
				BukkitConverters.getSetConverter(FLAGS_CLASS, EnumWrappers
						.getGenericConverter(PlayerTeleportFlag.class)));
	}
}
