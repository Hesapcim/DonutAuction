package io.nightbeam.donutauction;

import io.nightbeam.donutauction.command.AuctionCommand;
import io.nightbeam.donutauction.economy.VaultEconomyProvider;
import io.nightbeam.donutauction.gui.GuiManager;
import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.hook.HookManager;
import io.nightbeam.donutauction.listener.AuctionChatListener;
import io.nightbeam.donutauction.listener.AuctionInventoryListener;
import io.nightbeam.donutauction.listener.PlayerQuitListener;
import io.nightbeam.donutauction.service.AuctionManager;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.storage.AuctionRepository;
import io.nightbeam.donutauction.storage.DatabaseManager;
import io.nightbeam.donutauction.storage.SqlAuctionRepository;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.SchedulerAdapter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class AuctionHousePlugin extends JavaPlugin {

    private SchedulerAdapter schedulerAdapter;
    private VaultEconomyProvider economyProvider;
    private DatabaseManager databaseManager;
    private AuctionRepository auctionRepository;
    private AuctionManager auctionManager;
    private AuctionService auctionService;
    private GuiManager guiManager;
    private DonutCoreHook donutCoreHook;
    private MessageUtil messages;
    private MessageUtil auctionGuiText;
    private MessageUtil filterGuiText;
    private MessageUtil playerAuctionsGuiText;
    private MessageUtil adminRemovalGuiText;
    private Set<Material> blockedMaterials = Set.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfiguration();

        this.schedulerAdapter = new SchedulerAdapter(this);
        this.economyProvider = VaultEconomyProvider.create(this)
                .orElseThrow(() -> new IllegalStateException("Vault economy provider was not found."));
        this.databaseManager = DatabaseManager.fromConfig(this);
        this.databaseManager.start();

        this.auctionRepository = new SqlAuctionRepository(databaseManager);
        this.auctionManager = new AuctionManager(getConfig().getInt("auction.browse-page-size", 45));
        this.donutCoreHook = HookManager.create(this);
        this.auctionService = new AuctionService(this, schedulerAdapter, economyProvider, auctionRepository, auctionManager, donutCoreHook);
        this.guiManager = new GuiManager(this, auctionService, auctionManager, donutCoreHook);

        this.auctionService.initialize();
        registerCommands();
        registerListeners();

        getLogger().info("DonutAuctionHouse enabled using " + databaseManager.getDatabaseType().name() + " storage.");
    }

    @Override
    public void onDisable() {
        if (auctionService != null) {
            auctionService.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    private void registerCommands() {
        AuctionCommand auctionCommand = new AuctionCommand(this, auctionService, guiManager);
        registerCommand("ah", auctionCommand);
        registerCommand("auction", auctionCommand);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new AuctionInventoryListener(guiManager), this);
        getServer().getPluginManager().registerEvents(new AuctionChatListener(guiManager), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(guiManager), this);
    }

    private void registerCommand(String name, AuctionCommand command) {
        PluginCommand pluginCommand = getCommand(name);
        if (pluginCommand == null) {
            throw new IllegalStateException("Missing command in plugin.yml: " + name);
        }

        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }

    public SchedulerAdapter schedulerAdapter() {
        return schedulerAdapter;
    }

    public MessageUtil messages() {
        return messages;
    }

    public MessageUtil auctionGuiText() {
        return auctionGuiText;
    }

    public MessageUtil filterGuiText() {
        return filterGuiText;
    }

    public MessageUtil playerAuctionsGuiText() {
        return playerAuctionsGuiText;
    }

    public MessageUtil adminRemovalGuiText() {
        return adminRemovalGuiText;
    }

    public boolean isBlockedMaterial(Material material) {
        return blockedMaterials.contains(material);
    }

    public void reloadPluginConfiguration() {
        reloadConfig();
        applyConfigDefaults();
        loadBlockedMaterials();
        this.messages = loadTextConfiguration("messages.yml", true);
        this.auctionGuiText = loadTextConfiguration("guis/auction.yml", false);
        this.filterGuiText = loadTextConfiguration("guis/filter.yml", false);
        this.playerAuctionsGuiText = loadTextConfiguration("guis/player-auctions.yml", false);
        this.adminRemovalGuiText = loadTextConfiguration("guis/admin-removal.yml", false);
    }

    public void applyConfigDefaults() {
        getConfig().addDefault("auction.min-price", 10.0D);
        getConfig().addDefault("auction.max-price", 1.0E9D);
        getConfig().addDefault("blocked-materials", List.of());
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    private void loadBlockedMaterials() {
        Set<Material> loadedMaterials = EnumSet.noneOf(Material.class);
        for (String materialName : getConfig().getStringList("blocked-materials")) {
            try {
                loadedMaterials.add(Material.valueOf(materialName.toUpperCase(Locale.ENGLISH)));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Ignoring invalid blocked material: " + materialName);
            }
        }
        this.blockedMaterials = Set.copyOf(loadedMaterials);
    }

    private MessageUtil loadTextConfiguration(String resourcePath, boolean usePrefix) {
        return new MessageUtil(loadConfigurationFile(resourcePath), usePrefix);
    }

    private FileConfiguration loadConfigurationFile(String resourcePath) {
        File targetFile = new File(getDataFolder(), resourcePath);
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (!targetFile.exists()) {
            saveResource(resourcePath, false);
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(targetFile);
        try (InputStream inputStream = getResource(resourcePath)) {
            if (inputStream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                configuration.setDefaults(defaults);
                configuration.options().copyDefaults(true);
                configuration.save(targetFile);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load configuration file " + resourcePath, exception);
        }

        return configuration;
    }
}
