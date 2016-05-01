import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;


public class FreezePlugin extends JavaPlugin {
	private ProtocolManager protocolManager;
	private Freezer freezer;
	
	@Override
	public void onEnable() {
		this.protocolManager = ProtocolLibrary.getProtocolManager();
		this.freezer = new Freezer(protocolManager, this, ListenerPriority.NORMAL);
		protocolManager.addPacketListener(freezer);
		
		getCommand("freeze").setExecutor(new CommandExecutor() {
			@Override
			public boolean onCommand(CommandSender sender, Command command,
					String label, String[] args) {
				freezer.freezePlayer((Player)sender);
				return true;
			}
		});
		getCommand("unfreeze").setExecutor(new CommandExecutor() {
			@Override
			public boolean onCommand(CommandSender sender, Command command,
					String label, String[] args) {
				freezer.unfreezePlayer((Player)sender);
				return true;
			}
		});
	}
}
