package dk.noxitech.hibernate;

import dk.noxitech.hibernate.utils.ColorUtils;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.scheduler.BukkitTask;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;

public class Main extends JavaPlugin implements Listener {


    private final AtomicBoolean isHibernating = new AtomicBoolean(false);
    private final AtomicBoolean isDeepHibernating = new AtomicBoolean(false);
    private final AtomicBoolean countdownActive = new AtomicBoolean(false);
    private final AtomicInteger countdownTimer = new AtomicInteger(0);
    private final AtomicInteger playerCount = new AtomicInteger(0);

    private final Set<Integer> suspendedTaskIds = new HashSet<>();
    private HibernateConfig hibernateConfig;
    private ConsoleCommandSender log;
    private FileConfiguration config;

    private BukkitTask hibernationTask;
    private BukkitTask chunkUnloadTask;

    private OperatingSystemMXBean osBean;
    private Method getCpuUsageMethod;
    private double cpuUsageBeforeHibernation;
    private long cpuTimeBeforeHibernation;
    private Thread mainServerThread;

    private static class HibernateConfig {
        volatile boolean enabled = true;
        volatile boolean unloadChunks = true;
        volatile boolean deepHibernation = false;
        volatile long sleepMillis = 1000L;
        volatile int hibernationCountdown = 30;
        volatile Set<String> blacklist = new HashSet<>();
        volatile boolean hasBlacklistedPlugins = false;

        volatile boolean cpuOptimization = true;
        volatile boolean suspendOtherTasks = false;
        volatile boolean lowerThreadPriority = true;
        volatile boolean monitorCpuUsage = true;
    }

    public Set<Integer> getSuspendedTaskIds() {
        return new HashSet<>(suspendedTaskIds);
    }

    public void setSuspendedTaskIds(Set<Integer> suspendedTaskIds) {
        synchronized (this.suspendedTaskIds) {
            this.suspendedTaskIds.clear();
            this.suspendedTaskIds.addAll(suspendedTaskIds);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (hibernationTask != null) {
                hibernationTask.cancel();
            }
            if (chunkUnloadTask != null) {
                chunkUnloadTask.cancel();
            }

            Bukkit.getScheduler().cancelTasks(this);
            wakeUpServer();
            log.sendMessage(ColorUtils.getColored("&a[Hibernate] Plugin disabled and server awakened"));
        } catch (Exception e) {
            getLogger().warning("Error during plugin disable: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("hibernate") ||
                !(sender.isOp() || sender.hasPermission("hibernate.admin"))) {
            return false;
        }

        if (args.length != 1) {
            sender.sendMessage(ColorUtils.getColored("&c[Hibernate] Usage: /hibernate <reload|status|wake|sleep>"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                handleReload(sender);
                break;
            case "status":
                handleStatus(sender);
                break;
            default:
                sender.sendMessage(ColorUtils.getColored("&c[Hibernate] Unknown command. Use: reload, status, wake, or sleep"));
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        reloadConfig();
        loadConfiguration();
        sender.sendMessage(ColorUtils.getColored("&a[Hibernate] Configuration reloaded!"));

        restartTasks();
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage(ColorUtils.getColored(String.format("&a[Hibernate] Hibernation is currently &%s%s",
                hibernateConfig.enabled ? "a" : "c",
                hibernateConfig.enabled ? "enabled" : "disabled")));
        sender.sendMessage(ColorUtils.getColored(String.format("&a[Hibernate] Deep hibernation is &%s%s",
                hibernateConfig.deepHibernation ? "a" : "c",
                hibernateConfig.deepHibernation ? "enabled" : "disabled")));
        sender.sendMessage(ColorUtils.getColored(String.format("&a[Hibernate] Hibernation countdown: &e%d seconds",
                hibernateConfig.hibernationCountdown)));

        String serverState = getServerState();
        String color = getServerStateColor();
        sender.sendMessage(ColorUtils.getColored(String.format("&a[Hibernate] The server is currently &%s%s", color, serverState)));
    }

    private String getServerState() {
        int onlinePlayerCount = getServer().getOnlinePlayers().size();

        if (countdownActive.get()) {
            return "preparing for hibernation (" + countdownTimer.get() + "s)";
        } else if (onlinePlayerCount == 0) {
            if (isDeepHibernating.get()) {
                return "deep sleeping";
            } else if (isHibernating.get()) {
                return "sleeping";
            }
        }
        return "awake";
    }

    private String getServerStateColor() {
        int onlinePlayerCount = getServer().getOnlinePlayers().size();

        if (countdownActive.get()) {
            return "6";
        } else if (onlinePlayerCount == 0) {
            if (isDeepHibernating.get()) {
                return "c";
            } else if (isHibernating.get()) {
                return "e";
            }
        }
        return "a";
    }

    @Override
    public void onEnable() {
        log = Bukkit.getConsoleSender();
        saveDefaultConfig();
        loadConfiguration();

        Set<String> foundBlacklistedPlugins = checkBlacklistedPlugins();
        hibernateConfig.hasBlacklistedPlugins = !foundBlacklistedPlugins.isEmpty();

        if (!hibernateConfig.hasBlacklistedPlugins && hibernateConfig.enabled) {
            startHibernationTask();
            log.sendMessage(ColorUtils.getColored("&a[Hibernate] Hibernate system started successfully!"));
            if (hibernateConfig.deepHibernation) {
                log.sendMessage(ColorUtils.getColored("&6[Hibernate] Deep hibernation mode is ENABLED - Use with caution!"));
            }
        } else if (hibernateConfig.hasBlacklistedPlugins) {
            log.sendMessage(ColorUtils.getColored("&c[Hibernate] Blacklisted plugins found. Standard hibernation disabled."));
            log.sendMessage(ColorUtils.getColored("&4[Hibernate] Blacklisted plugins: " + String.join(", ", foundBlacklistedPlugins)));
        }

        if (hibernateConfig.unloadChunks) {
            startChunkUnloadTask();
        }

        getServer().getPluginManager().registerEvents(this, this);

        playerCount.set(getServer().getOnlinePlayers().size());

        initializeCpuMonitoring();
    }
    
    private void initializeCpuMonitoring() {
        try {
            osBean = ManagementFactory.getOperatingSystemMXBean();

            try {
                getCpuUsageMethod = osBean.getClass().getMethod("getProcessCpuLoad");
                getCpuUsageMethod.setAccessible(true);
            } catch (NoSuchMethodException e) {
                try {
                    getCpuUsageMethod = osBean.getClass().getMethod("getSystemCpuLoad");
                    getCpuUsageMethod.setAccessible(true);
                } catch (NoSuchMethodException ex) {
                    log.sendMessage(ColorUtils.getColored("&6[Hibernate] CPU monitoring not available on this JVM"));
                    hibernateConfig.monitorCpuUsage = false;
                }
            }
            
            mainServerThread = Thread.currentThread();
            
        } catch (Exception e) {
            log.sendMessage(ColorUtils.getColored("&c[Hibernate] Failed to initialize CPU monitoring: " + e.getMessage()));
            hibernateConfig.monitorCpuUsage = false;
        }
    }
    
    private double getCurrentCpuUsage() {
        if (!hibernateConfig.monitorCpuUsage || getCpuUsageMethod == null) {
            return -1.0;
        }

        try {
            Object result = getCpuUsageMethod.invoke(osBean);
            if (result instanceof Double) {
                return (Double) result;
            }
        } catch (Exception ignored) {}
        return -1.0;
    }

    private void suspendOtherPluginTasks() {
        if (!hibernateConfig.suspendOtherTasks) {
            return;
        }

        try {
            synchronized (suspendedTaskIds) {
                suspendedTaskIds.clear();

                Bukkit.getScheduler().getPendingTasks().forEach(task -> {
                    if (task.getOwner() != this && !isEssentialPlugin(task.getOwner().getName())) {
                        suspendedTaskIds.add(task.getTaskId());
                        Bukkit.getScheduler().cancelTask(task.getTaskId());
                    }
                });

                if (!suspendedTaskIds.isEmpty()) {
                    log.sendMessage(ColorUtils.getColored("&6[Hibernate] Suspended " + suspendedTaskIds.size() + " tasks from other plugins"));
                }
            }
        } catch (Exception e) {
            log.sendMessage(ColorUtils.getColored("&c[Hibernate] Error suspending tasks: " + e.getMessage()));
        }
    }
    
    private void resumeOtherPluginTasks() {
        if (!hibernateConfig.suspendOtherTasks || suspendedTaskIds.isEmpty()) {
            return;
        }
        
        try {
            synchronized (suspendedTaskIds) {
                if (!suspendedTaskIds.isEmpty()) {
                    log.sendMessage(ColorUtils.getColored("&6[Hibernate] Note: " + suspendedTaskIds.size() + " plugin tasks were suspended - plugins may need to restart their schedulers"));
                }
                suspendedTaskIds.clear();
            }
        } catch (Exception e) {
            log.sendMessage(ColorUtils.getColored("&c[Hibernate] Error during task resume: " + e.getMessage()));
        }
    }
    
    private boolean isEssentialPlugin(String pluginName) {
        String name = pluginName.toLowerCase();
        return name.equals("worldedit") || 
               name.equals("worldguard") || 
               name.equals("vault") || 
               name.equals("essentials") ||
               name.equals("luckperms") ||
               name.equals("coreprotect") ||
               name.equals("dynmap");
    }

    private void adjustThreadPriority(boolean lowerPriority) {
        if (!hibernateConfig.lowerThreadPriority) {
            return;
        }

        try {
            int newPriority = lowerPriority ? Thread.MIN_PRIORITY + 1 : Thread.NORM_PRIORITY;

            if (mainServerThread != null) {
                mainServerThread.setPriority(newPriority);
            }

            Thread currentThread = Thread.currentThread();
            if (currentThread != mainServerThread) {
                currentThread.setPriority(newPriority);
            }

            String action = lowerPriority ? "lowered" : "restored";
            log.sendMessage(ColorUtils.getColored("&6[Hibernate] Thread priority " + action + " to " + newPriority));
        } catch (Exception e) {
            log.sendMessage(ColorUtils.getColored("&c[Hibernate] Failed to adjust thread priority: " + e.getMessage()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        int newCount = playerCount.incrementAndGet();

        if (newCount > 0 && (countdownActive.get() || isHibernating.get() || isDeepHibernating.get())) {
            Bukkit.getScheduler().runTaskLater(this, this::wakeUpServer, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            playerCount.set(getServer().getOnlinePlayers().size());
        }, 1L);
    }

    private void loadConfiguration() {
        hibernateConfig = new HibernateConfig();
        config = getConfig();

        config.addDefault("enabled", true);
        config.addDefault("unloadChunks", true);
        config.addDefault("deepHibernation", false);
        config.addDefault("sleepMillis", 1000L);
        config.addDefault("hibernationCountdown", 30);
        config.addDefault("blacklist", new ArrayList<String>());
        config.addDefault("cpuOptimization", true);
        config.addDefault("suspendOtherTasks", false);
        config.addDefault("lowerThreadPriority", true);
        config.addDefault("monitorCpuUsage", true);
        config.options().copyDefaults(true);

        hibernateConfig.enabled = config.getBoolean("enabled");
        hibernateConfig.unloadChunks = config.getBoolean("unloadChunks");
        hibernateConfig.deepHibernation = config.getBoolean("deepHibernation");
        hibernateConfig.sleepMillis = Math.max(100L, config.getLong("sleepMillis"));
        hibernateConfig.hibernationCountdown = Math.max(5, config.getInt("hibernationCountdown"));
        hibernateConfig.blacklist = loadBlacklistFromConfig(config);
        hibernateConfig.cpuOptimization = config.getBoolean("cpuOptimization");
        hibernateConfig.suspendOtherTasks = config.getBoolean("suspendOtherTasks");
        hibernateConfig.lowerThreadPriority = config.getBoolean("lowerThreadPriority");
        hibernateConfig.monitorCpuUsage = config.getBoolean("monitorCpuUsage");

        saveConfig();
    }

    private Set<String> loadBlacklistFromConfig(FileConfiguration config) {
        Set<String> blacklistSet = new HashSet<>();
        Set<String> rawBlacklist = new HashSet<>(config.getStringList("blacklist"));

        for (String entry : rawBlacklist) {
            if (entry.startsWith("http")) {
                loadBlacklistFromUrl(entry, blacklistSet);
            } else {
                blacklistSet.add(entry.toLowerCase().trim());
            }
        }
        return blacklistSet;
    }

    private void loadBlacklistFromUrl(String url, Set<String> blacklistSet) {
        try (Scanner scanner = new Scanner(new URL(url).openStream(), "UTF-8")) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    blacklistSet.add(line.toLowerCase());
                }
            }
        } catch (IOException e) {
            getLogger().severe("Failed to load blacklist from URL: " + url + " - " + e.getMessage());
        }
    }

    private Set<String> checkBlacklistedPlugins() {
        Set<String> foundBlacklisted = new HashSet<>();
        Plugin[] plugins = getServer().getPluginManager().getPlugins();

        for (Plugin plugin : plugins) {
            if (hibernateConfig.blacklist.contains(plugin.getName().toLowerCase())) {
                foundBlacklisted.add(plugin.getName());
            }
        }

        return foundBlacklisted;
    }

    private void restartTasks() {
        if (hibernationTask != null) {
            hibernationTask.cancel();
            hibernationTask = null;
        }
        if (chunkUnloadTask != null) {
            chunkUnloadTask.cancel();
            chunkUnloadTask = null;
        }

        if (hibernateConfig.enabled && !hibernateConfig.hasBlacklistedPlugins) {
            startHibernationTask();
        }
        if (hibernateConfig.unloadChunks) {
            startChunkUnloadTask();
        }
    }

    private void startHibernationTask() {
        hibernationTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                processHibernationCycle();
            } catch (Exception e) {
                getLogger().warning("Error in hibernation task: " + e.getMessage());
            }
        }, 0L, 20L);
    }

    private void processHibernationCycle() {
        int currentPlayerCount = playerCount.get();

        if (currentPlayerCount > 0 && (countdownActive.get() || isHibernating.get() || isDeepHibernating.get())) {
            wakeUpServer();
            return;
        }

        if (currentPlayerCount == 0 && hibernateConfig.enabled) {
            if (!countdownActive.get() && !isHibernating.get() && !isDeepHibernating.get()) {
                startHibernationCountdown();
            } else if (countdownActive.get()) {
                processCountdown();
            } else if (!isDeepHibernating.get() && hibernateConfig.deepHibernation) {
                deepHibernation();
            } else if (!hibernateConfig.deepHibernation && !isHibernating.get()) {
                performStandardHibernation();
            }
        }
    }

    private void startHibernationCountdown() {
        String hibernationType = hibernateConfig.deepHibernation ? "Deep Hibernation" : "Hibernation";
        log.sendMessage(ColorUtils.getColored("&6[Hibernate] Saving worlds before " + hibernationType));

        for (World world : getServer().getWorlds()) {
            world.save();
        }

        log.sendMessage(ColorUtils.getColored("&6[Hibernate] Worlds saved!"));

        countdownActive.set(true);
        countdownTimer.set(hibernateConfig.hibernationCountdown);
        log.sendMessage(ColorUtils.getColored("&6[Hibernate] Starting hibernation countdown: " + countdownTimer.get() + " seconds"));
    }

    private void processCountdown() {
        int timer = countdownTimer.decrementAndGet();

        if (timer == 20 || timer == 10 || (timer <= 5 && timer > 0)) {
            Bukkit.broadcastMessage(ColorUtils.getColored("&6[Hibernate] Server will hibernate in " + timer + " seconds..."));
        }

        if (timer <= 0) {
            countdownActive.set(false);
            log.sendMessage(ColorUtils.getColored("&6[Hibernate] Hibernation countdown completed - Entering hibernation mode"));

            try {
                if (hibernateConfig.deepHibernation) {
                    deepHibernation();
                } else {
                    performStandardHibernation();
                }
                log.sendMessage(ColorUtils.getColored("&6[Hibernate] Hibernation cycle completed"));
            } catch (Exception e) {
                log.sendMessage(ColorUtils.getColored("&c[Hibernate] Error during hibernation: " + e.getMessage()));
            }
        }
    }

    private void performStandardHibernation() {
        if (!isHibernating.get()) {
            isHibernating.set(true);

            if (hibernateConfig.cpuOptimization) {
                cpuUsageBeforeHibernation = getCurrentCpuUsage();
                if (cpuUsageBeforeHibernation > 0) {
                    log.sendMessage(ColorUtils.getColored("&6[Hibernate] CPU usage before hibernation: " + String.format("%.2f%%", cpuUsageBeforeHibernation * 100)));
                }
                
                adjustThreadPriority(true);
                suspendOtherPluginTasks();
            }

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    Thread.sleep(hibernateConfig.sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                Bukkit.getScheduler().runTask(this, this::unloadChunksAndCleanMemory);
            });
        }
    }    private void startChunkUnloadTask() {
        chunkUnloadTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (playerCount.get() == 0) {
                unloadChunksAndCleanMemory();
            }
        }, 0L, 20L * 60L);
    }

    private void deepHibernation() {
        if (playerCount.get() == 0 && !isDeepHibernating.get()) {
            isDeepHibernating.set(true);
            isHibernating.set(false);
            log.sendMessage(ColorUtils.getColored("&6[Hibernate] === ENTERING DEEP HIBERNATION MODE ==="));

            if (hibernateConfig.cpuOptimization) {
                cpuUsageBeforeHibernation = getCurrentCpuUsage();
                if (cpuUsageBeforeHibernation > 0) {
                    log.sendMessage(ColorUtils.getColored("&6[Hibernate] CPU usage before deep hibernation: " + String.format("%.2f%%", cpuUsageBeforeHibernation * 100)));
                }

                adjustThreadPriority(true);
                suspendOtherPluginTasks();
            }

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    unloadAllChunksAggressively();
                    freezeServerProcesses();
                    performAggressiveGarbageCollection();
                    Thread.sleep(hibernateConfig.sleepMillis * 5);

                    Bukkit.getScheduler().runTask(this, () -> {
                        log.sendMessage(ColorUtils.getColored("&a[Hibernate] Deep hibernation cycle completed"));

                        if (hibernateConfig.cpuOptimization && hibernateConfig.monitorCpuUsage) {
                            double currentCpu = getCurrentCpuUsage();
                            if (currentCpu > 0 && cpuUsageBeforeHibernation > 0) {
                                double reduction = ((cpuUsageBeforeHibernation - currentCpu) / cpuUsageBeforeHibernation) * 100;
                                log.sendMessage(ColorUtils.getColored("&a[Hibernate] CPU usage reduced by: " + String.format("%.2f%%", reduction)));
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    getLogger().warning("Error during deep hibernation: " + e.getMessage());
                }
            });
        }
    }    private void unloadAllChunksAggressively() {
        Bukkit.getScheduler().runTask(this, () -> {
            int totalUnloaded = 0;
            for (World world : Bukkit.getWorlds()) {
                world.save();
                Chunk[] chunks = world.getLoadedChunks();
                for (Chunk chunk : chunks) {
                    try {
                        if (chunk.unload(true)) {
                            totalUnloaded++;
                        }
                    } catch (Exception ignored) {}
                }
            }

            final int unloadedCount = totalUnloaded;
            log.sendMessage(ColorUtils.getColored("&a[Hibernate] Deep hibernation: Aggressively unloaded " + unloadedCount + " chunks"));
        });
    }

    private void freezeServerProcesses() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                for (World world : Bukkit.getWorlds()) {
                    world.setSpawnFlags(false, false);
                    world.setKeepSpawnInMemory(false);
                    world.setAutoSave(false);
                }
                log.sendMessage(ColorUtils.getColored("&a[Hibernate] Server processes frozen for deep hibernation"));
            } catch (Exception e) {
                log.sendMessage(ColorUtils.getColored("&c[Hibernate] Could not freeze all processes: " + e.getMessage()));
            }
        });
    }

    private void wakeUpServer() {
        boolean wasDeepHibernating = isDeepHibernating.getAndSet(false);
        boolean wasHibernating = isHibernating.getAndSet(false);
        boolean wasCountdownActive = countdownActive.getAndSet(false);

        if (wasCountdownActive) {
            countdownTimer.set(0);
            log.sendMessage(ColorUtils.getColored("&6[Hibernate] Hibernation countdown cancelled"));
        }

        if (hibernateConfig.cpuOptimization && (wasHibernating || wasDeepHibernating)) {
            adjustThreadPriority(false);
            resumeOtherPluginTasks();

            if (hibernateConfig.monitorCpuUsage) {
                double currentCpu = getCurrentCpuUsage();
                if (currentCpu > 0) {
                    log.sendMessage(ColorUtils.getColored("&a[Hibernate] Current CPU usage: " + String.format("%.2f%%", currentCpu * 100)));
                }
            }
        }

        if (wasDeepHibernating) {
            log.sendMessage(ColorUtils.getColored("&a[Hibernate] Waking up from deep hibernation..."));
            restoreServerProcesses();
            performGarbageCollection();
            log.sendMessage(ColorUtils.getColored("&a[Hibernate] Server awakened from deep hibernation!"));
        } else if (wasHibernating) {
            log.sendMessage(ColorUtils.getColored("&a[Hibernate] Server awakened from normal hibernation"));
        }
    }

    private void restoreServerProcesses() {
        for (World world : Bukkit.getWorlds()) {
            world.setSpawnFlags(true, true);
            world.setKeepSpawnInMemory(true);
            world.setAutoSave(true);
        }
    }

    private void unloadChunksAndCleanMemory() {
        if (isDeepHibernating.get()) {
            return;
        }

        int unloadedCount = 0;
        for (World world : Bukkit.getWorlds()) {
            Chunk[] loadedChunks = world.getLoadedChunks();
            for (Chunk chunk : loadedChunks) {
                try {
                    if (chunk.unload(true)) {
                        unloadedCount++;
                    }
                } catch (Exception e) {
                }
            }
        }

        if (unloadedCount > 0) {
            log.sendMessage(ColorUtils.getColored(String.format("&a[Hibernate] Unloaded %d chunks", unloadedCount)));
            performGarbageCollection();
        }
    }

    private void performGarbageCollection() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            long memoryBefore = Runtime.getRuntime().freeMemory();
            System.gc();
            long memoryAfter = Runtime.getRuntime().freeMemory();
            long memoryFreed = (memoryAfter - memoryBefore) / 1024L / 1024L;

            if (memoryFreed > 0L) {
                Bukkit.getScheduler().runTask(this, () -> {
                    log.sendMessage(ColorUtils.getColored(String.format("&a[Hibernate] %d MB memory freed", memoryFreed)));
                });
            }
        });
    }

    private void performAggressiveGarbageCollection() {
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
    }
}