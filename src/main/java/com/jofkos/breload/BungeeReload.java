/*
 * Copyright (c) 2016 Jofkos. All rights reserved.
 */

package com.jofkos.breload;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;

public class BungeeReload extends Plugin {

	private ReloadCommand reloadCommand;

	@Override
	public void onEnable() {
		ProxyServer.getInstance().getPluginManager().registerCommand(this, reloadCommand = new ReloadCommand());
	}

	@Override
	public void onDisable() {
		reloadCommand.updatePlugins();
	}
}
