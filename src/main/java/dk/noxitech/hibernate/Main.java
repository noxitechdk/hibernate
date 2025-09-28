package dk.noxitech.hibernate;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin implements Listener {
    private static class HibernateConfig {
        boolean enabled = false;
        boolean unloadChunks = false;
        boolean deepHibernation = false;
        long sleepMillis = 1000L;
        int hibernationCountdown = 30;
        Set<String> blacklist = new HashSet<String>();
        int playerCheckCount = 0;
    }

    private HibernateConfig hibernateConfig = new HibernateConfig();
    private FileConfiguration config = getConfig();
    private static boolean isHibernating = false;
    private static boolean isDeepHibernating = false;
    private static boolean countdownActive = false;
    private static int countdownTimer = 0;
    private Set<Integer> suspendedTaskIds = new HashSet<Integer>();

    @Override
    public void onDisable() {
        try {
            Bukkit.getScheduler().cancelTasks(this);
            wakeUpServer();
        } catch (Exception e) {}
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("hibernate") &&
            (sender.isOp() || sender.hasPermission("hibernate.admin"))) {
            if (args.length == 1) {
                if (args[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    loadConfiguration();
                    sender.sendMessage("§a[Hibernate] Configuration reloaded!");
                    return true;
                }
                if (args[0].equalsIgnoreCase("status")) {
                    sender.sendMessage(String.format("§a[Hibernate] Hibernation is currently §%s%s",
                        hibernateConfig.enabled ? "a" : "c",
                        hibernateConfig.enabled ? "enabled" : "disabled"));
                    sender.sendMessage(String.format("§a[Hibernate] Deep hibernation is §%s%s",
                        hibernateConfig.deepHibernation ? "a" : "c",
                        hibernateConfig.deepHibernation ? "enabled" : "disabled"));
                    sender.sendMessage(String.format("§a[Hibernate] Hibernation countdown: §e%d seconds",
                        hibernateConfig.hibernationCountdown));
                    
                    int onlinePlayerCount = getServer().getOnlinePlayers().size();
                    String serverState = "awake";
                    String color = "a";
                    
                    if (countdownActive) {
                        serverState = "preparing for hibernation (" + countdownTimer + "s)";
                        color = "6";
                    } else if (onlinePlayerCount == 0) {
                        if (isDeepHibernating) {
                            serverState = "deep sleeping";
                            color = "c";
                        } else if (isHibernating) {
                            serverState = "sleeping";
                            color = "e";
                        }
                    }
                    sender.sendMessage(String.format("§a[Hibernate] The server is currently §%s%s", color, serverState));
                    return true;
                }
                if (args[0].equalsIgnoreCase("wake")) {
                    if (countdownActive) {
                        countdownActive = false;
                        countdownTimer = 0;
                        sender.sendMessage("§a[Hibernate] Hibernation countdown cancelled!");
                    } else if (isDeepHibernating || isHibernating) {
                        wakeUpServer();
                        sender.sendMessage("§a[Hibernate] Server has been manually awakened!");
                    } else {
                        sender.sendMessage("§c[Hibernate] Server is already awake and no countdown is active!");
                    }
                    return true;
                }
                if (args[0].equalsIgnoreCase("sleep")) {
                    if (!isDeepHibernating && !isHibernating) {
                        if (hibernateConfig.deepHibernation) {
                            deepHibernation();
                        } else {
                            unloadChunksAndCleanMemory();
                        }
                        sender.sendMessage("§a[Hibernate] Server has been manually put to sleep!");
                    } else {
                        sender.sendMessage("§c[Hibernate] Server is already hibernating!");
                    }
                    return true;
                }
            } else {
                sender.sendMessage("§c[Hibernate] Usage: /hibernate <reload|status|wake|sleep>");
                return true;
            }
        }
        return false;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        Set<String> foundBlacklistedPlugins = checkBlacklistedPlugins();

        if (foundBlacklistedPlugins.isEmpty()) {
            startHibernationTask();
            isHibernating = true;
            getLogger().info("Hibernate system started successfully!");
            if (hibernateConfig.deepHibernation) {
                getLogger().info("Deep hibernation mode is ENABLED - Use with caution!");
            }
        } else {
            getLogger().warning("Blacklisted plugins were found (plugins that will stop working if Hibernate is enabled). " +
                "Standard hibernation will be disabled, but Hibernate will still try to unload chunks. " +
                "(check the Hibernate config and edit the blacklist if you want to force enable Hibernate)");
            getLogger().warning("Blacklisted plugins: " + String.join(", ", foundBlacklistedPlugins));
        }

        if (hibernateConfig.unloadChunks) {
            startChunkUnloadTask();
        }

        getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadConfiguration() {
        config.addDefault("enabled", true);
        config.addDefault("unloadChunks", true);
        config.addDefault("deepHibernation", false);
        config.addDefault("sleepMillis", 1000L);
        config.addDefault("hibernationCountdown", 30);
        config.addDefault("blacklist", new ArrayList<String>());
        config.options().copyDefaults(true);

        hibernateConfig.enabled = config.getBoolean("enabled");
        hibernateConfig.unloadChunks = config.getBoolean("unloadChunks");
        hibernateConfig.deepHibernation = config.getBoolean("deepHibernation");
        hibernateConfig.sleepMillis = config.getLong("sleepMillis");
        hibernateConfig.hibernationCountdown = config.getInt("hibernationCountdown");
        hibernateConfig.blacklist = loadBlacklistFromConfig(config);

        saveConfig();
    }

    private Set<String> loadBlacklistFromConfig(FileConfiguration config) {
        Set<String> blacklistSet = new HashSet<String>();
        Set<String> rawBlacklist = new HashSet<String>(config.getStringList("blacklist"));

        for (String entry : rawBlacklist) {
            if (entry.startsWith("http")) {
                try {
                    Scanner scanner = new Scanner(new URL(entry).openStream(), "UTF-8");
                    try {
                        while (scanner.hasNextLine()) {
                            String line = scanner.nextLine();
                            if (!line.equals("") && !line.startsWith("#")) {
                                blacklistSet.add(line.toLowerCase());
                            }
                        }
                    } finally {
                        scanner.close();
                    }
                } catch (IOException e) {
                    getLogger().severe("Failed to get blacklist from URL: " + entry);
                }
            } else {
                blacklistSet.add(entry.toLowerCase());
            }
        }
        return blacklistSet;
    }

    private Set<String> checkBlacklistedPlugins() {
        Set<String> foundBlacklisted = new HashSet<String>();
        Plugin[] plugins = getServer().getPluginManager().getPlugins();

        for (Plugin plugin : plugins) {
            if (hibernateConfig.blacklist.contains(plugin.getName().toLowerCase())) {
                foundBlacklisted.add(plugin.getName());
            }
        }

        return foundBlacklisted;
    }

    private void startHibernationTask() {
        final Main plugin = this;

        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                Server server = Bukkit.getServer();
                int onlineCount = server.getOnlinePlayers().size();

                if (onlineCount > 0 && countdownActive) {
                    countdownActive = false;
                    countdownTimer = 0;
                    getLogger().info("§6[Hibernate] Hibernation countdown cancelled - players joined!");
                    return;
                }

                if (onlineCount > 0 && (isDeepHibernating || (hibernateConfig.playerCheckCount == 0 && isHibernating))) {
                    wakeUpServer();
                    hibernateConfig.playerCheckCount = onlineCount;
                    return;
                }

                if (onlineCount == 0 && hibernateConfig.enabled) {
                    if (hibernateConfig.playerCheckCount != 0) {
                        String hibernationType = hibernateConfig.deepHibernation ? "Deep Hibernation" : "Hibernation";
                        getLogger().info("§6[Hibernate] Saving worlds before " + hibernationType);
                        for (World world : server.getWorlds()) {
                            world.save();
                        }
                        getLogger().info("§6[Hibernate] Worlds saved!");
                        hibernateConfig.playerCheckCount = 0;

                        countdownActive = true;
                        countdownTimer = hibernateConfig.hibernationCountdown;
                        getLogger().info("§6[Hibernate] Starting hibernation countdown: " + countdownTimer + " seconds");
                        return;
                    }

                    if (countdownActive) {
                        countdownTimer--;

                        if (countdownTimer == 20 || countdownTimer == 10 || countdownTimer <= 5 && countdownTimer > 0) {
                            Bukkit.broadcastMessage("§6[Hibernate] Server will hibernate in " + countdownTimer + " seconds...");
                        }

                        if (countdownTimer <= 0) {
                            countdownActive = false;
                            getLogger().info("§6[Hibernate] Countdown finished - starting hibernation!");
                            
                            if (hibernateConfig.deepHibernation && !isDeepHibernating) {
                                deepHibernation();
                            } else if (!hibernateConfig.deepHibernation) {
                                try {
                                    Thread.sleep(hibernateConfig.sleepMillis);
                                    unloadChunksAndCleanMemory();
                                } catch (Exception e) {}
                            }
                        }
                        return;
                    }

                    if (hibernateConfig.deepHibernation && !isDeepHibernating && !countdownActive) {
                        deepHibernation();
                    } else if (!hibernateConfig.deepHibernation && !countdownActive) {
                        try {
                            Thread.sleep(hibernateConfig.sleepMillis);
                            unloadChunksAndCleanMemory();
                        } catch (Exception e) {}
                    }
                } else {
                    hibernateConfig.playerCheckCount = onlineCount;
                }
            }
        }, 0L, 20L);
    }

    private void startChunkUnloadTask() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                if (getServer().getOnlinePlayers().size() == 0) {
                    unloadChunksAndCleanMemory();
                }
            }
        }, 0L, 20L * 60L);
    }

    private void deepHibernation() {
        if (getServer().getOnlinePlayers().size() == 0 && !isDeepHibernating) {
            isDeepHibernating = true;
            getLogger().info("=== ENTERING DEEP HIBERNATION MODE ===");

            unloadAllChunksAggressively();

            freezeServerProcesses();

            for (int i = 0; i < 3; i++) {
                System.gc();
                System.runFinalization();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                Thread.sleep(hibernateConfig.sleepMillis * 5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            getLogger().info("Deep hibernation cycle completed");
        }
    }

    private void unloadAllChunksAggressively() {
        int totalUnloaded = 0;
        for (World world : Bukkit.getWorlds()) {
            world.save();

            Chunk[] chunks = world.getLoadedChunks();
            for (Chunk chunk : chunks) {
                try {
                    boolean success = chunk.unload(true);
                    if (success) totalUnloaded++;
                } catch (Exception e) {}
            }
        }

        getLogger().info("Deep hibernation: Aggressively unloaded " + totalUnloaded + " chunks");
    }

    private void freezeServerProcesses() {
        try {
            for (World world : Bukkit.getWorlds()) {
                world.setSpawnFlags(false, false);
                world.setKeepSpawnInMemory(false);
                world.setAutoSave(false);
            }

            getLogger().info("Server processes frozen for deep hibernation");

        } catch (Exception e) {
            getLogger().warning("Could not freeze all processes: " + e.getMessage());
        }
    }

    private void wakeUpServer() {
        if (isDeepHibernating) {
            getLogger().info("=== WAKING UP FROM DEEP HIBERNATION ===");

            for (World world : Bukkit.getWorlds()) {
                world.setSpawnFlags(true, true);
                world.setKeepSpawnInMemory(true);
                world.setAutoSave(true);
            }

            System.gc();

            isDeepHibernating = false;
            getLogger().info("Server awakened from deep hibernation!");
        } else if (isHibernating) {
            getLogger().info("Server awakened from normal hibernation");
        }

        if (countdownActive) {
            countdownActive = false;
            countdownTimer = 0;
            getLogger().info("§6[Hibernate] Hibernation countdown cancelled due to server wake up");
        }

        isHibernating = false;
    }

    private void unloadChunksAndCleanMemory() {
        if (isDeepHibernating) {
            return;
        }

        int unloadedCount = 0;

        for (World world : Bukkit.getWorlds()) {
            Chunk[] loadedChunks = world.getLoadedChunks();
            for (Chunk chunk : loadedChunks) {
                if (chunk.unload(true)) {
                    unloadedCount++;
                }
            }
        }

        if (unloadedCount > 0) {
            getLogger().info(String.format("Unloaded %d chunks", unloadedCount));
            performGarbageCollection();
        }
    }

    private void performGarbageCollection() {
        long memoryBefore = Runtime.getRuntime().freeMemory();
        System.gc();
        long memoryAfter = Runtime.getRuntime().freeMemory();
        long memoryFreed = (memoryAfter - memoryBefore) / 1024L / 1024L;

        if (memoryFreed > 0L) {
            getLogger().info(String.format("%d MB memory freed using Java garbage collector", memoryFreed));
        }
    }
}