package org.efly;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;


public class PluginLoader extends Plugin {
	
	@Override
	public void onLoad() {
		RusherHackAPI.getModuleManager().registerFeature(new NCPElytraFly());
	}
	
	@Override
	public void onUnload() {
		this.getLogger().info("Example plugin unloaded!");
	}
	
}