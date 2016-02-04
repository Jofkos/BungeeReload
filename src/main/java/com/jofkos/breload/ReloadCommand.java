/*
 * Copyright (c) 2016 Jofkos. All rights reserved.
 */

package com.jofkos.breload;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.jofkos.breload.Reflect.FieldAccessor;
import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.*;
import net.md_5.bungee.command.*;
import net.md_5.bungee.module.ModuleManager;
import net.md_5.bungee.util.CaseInsensitiveSet;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.PropertyUtils;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Handler;
import java.util.logging.Level;

@SuppressWarnings({ "ConstantConditions", "ResultOfMethodCallIgnored", "deprecation" })
public class ReloadCommand extends Command {

	private FieldAccessor pluginManagerField = Reflect.getField(BungeeCord.class, "pluginManager");
	private FieldAccessor permissions = Reflect.getField(UserConnection.class, "permissions");
	private FieldAccessor groups = Reflect.getField(UserConnection.class, "groups");

	private ProxyServer proxy = ProxyServer.getInstance();
	private File updateDir = new File("updates"),
	pluginDir = new File("plugins"),
	modulesDir = new File("modules");

	private Yaml yaml;

	public ReloadCommand() {
		super("bungeereload", "network.reload", "breload");

		updateDir.mkdir();

		/*
		 * https://github.com/SpigotMC/BungeeCord/blob/ba448b5670946d4a744009320cdc9b758ad368cb/api/src/main/java/net/md_5/bungee/api/plugin/PluginManager.java#L63-L67
		 */
		Constructor yamlConstructor = new Constructor();
		PropertyUtils propertyUtils = yamlConstructor.getPropertyUtils();
		propertyUtils.setSkipMissingProperties(true);
		yamlConstructor.setPropertyUtils(propertyUtils);
		yaml = new Yaml(yamlConstructor);
	}

	@Override
	public void execute(CommandSender sender, String[] args) {
		try {

			proxy.getLogger().warning("§4[§cBReload§4]§r " + sender.getName() + " performed a bungee reload. This is experimental, please restart if something doesn't work like it should");

			if (sender instanceof ProxiedPlayer) {
				sender.sendMessage(TextComponent.fromLegacyText("§4[§cBReload§4]§r Reloading... Please restart your bungeecord if you encounter any issues."));
			}

			try {
				BungeeCord.getInstance().config.load();
				proxy.getConfigurationAdapter().load();

				for (ProxiedPlayer proxiedPlayer : ProxyServer.getInstance().getPlayers()) {
					groups.<Collection<String>>get(proxiedPlayer).clear();
					permissions.<Collection<String>>get(proxiedPlayer).clear();

					Collection<String> groups = proxy.getConfigurationAdapter().getGroups(proxiedPlayer.getName());
					groups.addAll(proxy.getConfigurationAdapter().getGroups(proxiedPlayer.getUniqueId().toString()));

					for (String group : groups) {
						proxiedPlayer.addGroups(group);
					}
				}
			} catch (Exception e) {
				proxy.getLogger().warning("Error while parsing your config.yml!");
				e.printStackTrace();
				return;
			}

			proxy.getLogger().info("Reloaded config and permissions from file");

			PluginManager pluginManager = proxy.getPluginManager();

			/*
			 * https://github.com/SpigotMC/BungeeCord/blob/ba448b5670946d4a744009320cdc9b758ad368cb/proxy/src/main/java/net/md_5/bungee/BungeeCord.java#L393-L410
			 */

			for (Plugin plugin : Lists.reverse(new ArrayList<>(pluginManager.getPlugins()))) {
				try {
					proxy.getLogger().info("Disabling plugin " + plugin.getDescription().getName());

					plugin.onDisable();
					for (Handler handler : plugin.getLogger().getHandlers()) {
						handler.close();
					}

					proxy.getScheduler().cancel(plugin);
					ExecutorService executor = plugin.getExecutorService();

					executor.shutdown();
					if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
						proxy.getLogger().log(Level.SEVERE, "The tasks owned by " + plugin.getDescription().getName() + " couldn't be terminated in time.");
					}

					if (!executor.isTerminated()) {
						executor.shutdownNow();
					}

					Thread.sleep(1L);

					pluginManager.unregisterCommands(plugin);
					pluginManager.unregisterListeners(plugin);

					((PluginClassloader) plugin.getClass().getClassLoader()).close();

				} catch (Throwable t) {
					proxy.getLogger().log(Level.SEVERE, "Exception disabling plugin " + plugin.getDescription().getName(), t);
				}
			}


			updatePlugins();

			pluginManagerField.set(proxy, pluginManager = new PluginManager(proxy));

			/*
			 * https://github.com/SpigotMC/BungeeCord/blob/ba448b5670946d4a744009320cdc9b758ad368cb/proxy/src/main/java/net/md_5/bungee/BungeeCord.java#L154-L158
			 */

			try {
				for (Command command : ImmutableList.of(new CommandReload(), new CommandEnd(), new CommandIP(), new CommandBungee(), new CommandPerms())) {
					proxy.getPluginManager().registerCommand(null, command);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}

			/*
			 * https://github.com/SpigotMC/BungeeCord/blob/ba448b5670946d4a744009320cdc9b758ad368cb/proxy/src/main/java/net/md_5/bungee/BungeeCord.java#L229-L256
			 */

			new ModuleManager().load(proxy, modulesDir);

			pluginManager.detectPlugins(modulesDir);

			pluginDir.mkdir();
			pluginManager.detectPlugins(pluginDir);
			pluginManager.loadPlugins();
			pluginManager.enablePlugins();

			if (sender instanceof ProxiedPlayer) {
				sender.sendMessage(TextComponent.fromLegacyText("§4[§cBReload§4]§r §aReload complete!"));
			}
			proxy.getLogger().info(sender.getName() + ": Reload complete!");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void updatePlugins() {
		if (!updateDir.exists()) updateDir.mkdir();
		File[] filesInDir = updateDir.listFiles();
		if (filesInDir == null || filesInDir.length <= 0) return;

		List<String> updatedFiles = Lists.newArrayList();

		/*
		 * https://github.com/SpigotMC/BungeeCord/blob/ba448b5670946d4a744009320cdc9b758ad368cb/api/src/main/java/net/md_5/bungee/api/plugin/PluginManager.java#L329-L359
		 */

		for (File file : updateDir.listFiles()) {
			if (file.isFile() && file.getName().endsWith(".jar")) {
				try (JarFile jar = new JarFile(file)) {
					JarEntry pdf = jar.getJarEntry("bungee.yml");
					if (pdf == null) {
						pdf = jar.getJarEntry("plugin.yml");
					}

					Preconditions.checkNotNull(pdf, "Plugin must have a plugin.yml or bungee.yml");

					try (InputStream in = jar.getInputStream(pdf)) {
						PluginDescription desc = yaml.loadAs(in, PluginDescription.class);
						desc.setFile(file);
						updatedFiles.add(desc.getName());

						Files.move(file, new File(pluginDir, file.getName()));
						proxy.getLogger().info(String.format("Updated plugin %s to version %s by %s", desc.getName(), desc.getVersion(), desc.getAuthor()));
					}
				} catch (Exception ex) {
					proxy.getLogger().log(Level.WARNING, "Could not load plugin from file " + file, ex);
				}
			}
		}
	}

}
