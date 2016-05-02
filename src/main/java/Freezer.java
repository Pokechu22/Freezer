import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

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
import com.comphenix.protocol.wrappers.WrappedAttribute;

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
			// Normal clients shouldn't hit this code in most cases, but
			// will at the very start if they have a bad ping
			event.setCancelled(true);

			resendPosition(event.getPlayer());
		} else if (event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
			// We want to cancel out the position portion but not the
			// look portion, so we resend the packet as a look packet
			PacketContainer oldPacket = event.getPacket();
			PacketContainer newPacket = protocolManager.createPacket(PacketType.Play.Client.LOOK);

			// Copy over fields to the new packet
			newPacket.getFloat().write(0, oldPacket.getFloat().read(0));  // Yaw
			newPacket.getFloat().write(1, oldPacket.getFloat().read(1));  // Pitch
			newPacket.getBooleans().write(0, oldPacket.getBooleans().read(0));  // On ground

			event.setPacket(newPacket);

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
		abilities.getFloat().write(1, player.getWalkSpeed());  // This only changes FOV; we want to leave FOV alone

		PacketContainer entityProperties = protocolManager.createPacket(PacketType.Play.Server.UPDATE_ATTRIBUTES);
		entityProperties.getIntegers().write(0, player.getEntityId());
		WrappedAttribute attribute = WrappedAttribute.newBuilder().packet(entityProperties).attributeKey("generic.movementSpeed").baseValue(0).build();
		entityProperties.getAttributeCollectionModifier().write(0, Arrays.asList(attribute));

		try {
			protocolManager.sendServerPacket(player, abilities);
			protocolManager.sendServerPacket(player, entityProperties);
		} catch (Exception e) {
			getPlugin().getLogger().log(Level.SEVERE, "Failed to send freeze start packets", e);
		}

		resendPosition(player);
	}

	private void endFreeze(Player player) {
		PacketContainer abilities = protocolManager.createPacket(PacketType.Play.Server.ABILITIES);

		abilities.getBooleans().write(0, false);  // Is invincible - currently unhandled
		abilities.getBooleans().write(1, player.isFlying());
		abilities.getBooleans().write(2, player.getAllowFlight());
		abilities.getBooleans().write(3, player.getGameMode() == GameMode.CREATIVE);
		abilities.getFloat().write(0, player.getFlySpeed());
		abilities.getFloat().write(1, player.getWalkSpeed());

		PacketContainer entityProperties = protocolManager.createPacket(PacketType.Play.Server.UPDATE_ATTRIBUTES);
		entityProperties.getIntegers().write(0, player.getEntityId());
		// Per the wiki, default is about .7 but actually .699999988079071
		WrappedAttribute attribute = WrappedAttribute.newBuilder().packet(entityProperties).attributeKey("generic.movementSpeed").baseValue(.699999988079071).build();
		entityProperties.getAttributeCollectionModifier().write(0, Arrays.asList(attribute));

		try {
			protocolManager.sendServerPacket(player, abilities);
			protocolManager.sendServerPacket(player, entityProperties);
		} catch (Exception e) {
			getPlugin().getLogger().log(Level.SEVERE, "Failed to send freeze end packets", e);
		}
	}

	private void resendPosition(Player player) {
		PacketContainer position = protocolManager.createPacket(PacketType.Play.Server.POSITION);
		Location location = player.getLocation();

		position.getDoubles().write(0, location.getX());
		position.getDoubles().write(1, location.getY());
		position.getDoubles().write(2, location.getZ());
		position.getFloat().write(0, 0f);  // No change in yaw
		position.getFloat().write(1, 0f);  // No change in pitch
		getFlagsModifier(position).write(0, EnumSet.of(PlayerTeleportFlag.X_ROT, PlayerTeleportFlag.Y_ROT));  // Mark pitch and yaw as relative
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
