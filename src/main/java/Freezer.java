import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
	private ProtocolManager protocolManager;

	/**
	 * All players currently frozen, by UUID.
	 */
	private Map<UUID, FrozenPlayerInfo> frozenPlayers = new HashMap<>();

	/**
	 * Stores state information about a player who is frozen.
	 */
	private static class FrozenPlayerInfo {
		/**
		 * Was the player able to fly when they were frozen?
		 */
		public final boolean allowFlight;
		/**
		 * Was the player actively flying when they were frozen?
		 */
		public final boolean isFlying;

		public FrozenPlayerInfo(boolean allowFlight, boolean isFlying) {
			this.allowFlight = allowFlight;
			this.isFlying = isFlying;
		}
	}

	public Freezer(ProtocolManager manager, Plugin plugin, ListenerPriority priority) {
		super(plugin, priority,
				PacketType.Play.Client.POSITION,
				PacketType.Play.Client.POSITION_LOOK,
				PacketType.Play.Server.ABILITIES);
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
	
	@Override
	public void onPacketSending(PacketEvent event) {
		if (!isFrozen(event.getPlayer())) {
			// Only process frozen players
			return;
		}

		if (event.getPacketType() == PacketType.Play.Server.ABILITIES) {
			if (!event.getPacket().getBooleans().read(1)) {
				// Not currently flying...
				event.setCancelled(true);
				return;
			}
			event.getPacket().getBooleans().write(1, true);  // Is flying
			event.getPacket().getBooleans().write(2, false);  // Can fly - when false, players can't toggle flight, even if they are already flying
			// Fly speed - changing this keeps players from rising or falling with shift / space, and also keeps them from moving.
			event.getPacket().getFloat().write(0, 0f);
			// We don't change walk speed since that only affects FOV, and the player is flying anyways.
		}
	}

	/**
	 * Checks if the given player is frozen.
	 */
	public boolean isFrozen(Player player) {
		return frozenPlayers.containsKey(player.getUniqueId());
	}

	/**
	 * Freezes the given player.  If the player is already frozen, nothing happens.
	 */
	public void freezePlayer(Player player) {
		if (!isFrozen(player)) {
			startFreeze(player);
		}
	}

	/**
	 * Unfreezes the given player.  If the player not currently frozen, nothing happens.
	 */
	public void unfreezePlayer(Player player) {
		if (isFrozen(player)) {
			endFreeze(player);
		}
	}

	/**
	 * Internal start freeze logic.
	 *
	 * Add the player to the frozen players list, then mark them as flying (so
	 * that Bukkit doesn't kick them for flying). Finally, resend their position
	 * and velocity.
	 */
	private void startFreeze(Player player) {
		frozenPlayers.put(player.getUniqueId(), new FrozenPlayerInfo(player.getAllowFlight(), player.isFlying()));

		// Tell Bukkit that this player is flying and shouldn't be effected by gravity.
		// The packet that is actually sent is modified in onPacketSending, and
		// the client won't receive these exact values.
		// However, changing the values also does cause the abilities packet to
		// be sent, meaning that it can be edited to have the info we want.
		// Sadly, we need to call two methods which means two packets when only
		// one packet really is needed, since Bukkit doesn't let one set
		// isFlying to true without allowing flight.
		player.setAllowFlight(true);
		player.setFlying(true);

		resendPosition(player);
	}

	/**
	 * Internal freeze end logic.
	 * 
	 * Remove the player from the frozen players list, and revert their flying state.
	 * @param player
	 */
	private void endFreeze(Player player) {
		FrozenPlayerInfo info = frozenPlayers.remove(player.getUniqueId());

		// Again, two packets are sent, though we do not modify them this time.
		player.setAllowFlight(info.allowFlight);
		player.setFlying(info.isFlying);
	}

	/**
	 * Sends a position packet and a velocity packet, with the position packet
	 * _only_ effecting position and not rotation.
	 */
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

	/**
	 * Internal logic for managing the flags field for the position packet
	 */
	private static final Class<?> FLAGS_CLASS = MinecraftReflection.getMinecraftClass("EnumPlayerTeleportFlags",
			"PacketPlayOutPosition$EnumPlayerTeleportFlags");

	/**
	 * Flags used to mark whether a change is aboslute or relative in the position packet.
	 * 
	 * If a flag is present, then the change is relative; otherwise, it is absolute.
	 */
	private static enum PlayerTeleportFlag {
		X, Y, Z, Y_ROT, X_ROT
	}

	/**
	 * Gets the structure flags structure modifier for the given player position packet.
	 */
	private StructureModifier<Set<PlayerTeleportFlag>> getFlagsModifier(PacketContainer packet) {
		return packet.getModifier().withType(Set.class,
				BukkitConverters.getSetConverter(FLAGS_CLASS, EnumWrappers
						.getGenericConverter(PlayerTeleportFlag.class)));
	}
}
