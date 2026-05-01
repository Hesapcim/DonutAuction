package io.nightbeam.donutauction.command;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.gui.GuiManager;
import io.nightbeam.donutauction.service.AuctionService;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private final AuctionHousePlugin plugin;
    private final AuctionService auctionService;
    private final GuiManager guiManager;

    public AuctionCommand(AuctionHousePlugin plugin, AuctionService auctionService, GuiManager guiManager) {
        this.plugin = plugin;
        this.auctionService = auctionService;
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "only-players", "&cOnly players can use this command.");
            return true;
        }

        if (!player.hasPermission("donutcore.auction.use")) {
            plugin.messages().send(player, "no-permission-use", "&cYou do not have permission to use the auction house.");
            return true;
        }

        if (args.length == 0) {
            guiManager.openAuctionHouse(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("sell")) {
            if (!player.hasPermission("donutcore.auction.sell")) {
                plugin.messages().send(player, "no-permission-sell", "&cYou do not have permission to sell items.");
                return true;
            }
            if (args.length < 2) {
                plugin.messages().send(player, "sell-usage", "&cUsage: /ah sell <price>");
                return true;
            }
            double price;
            try {
                price = Double.parseDouble(args[1]);
            } catch (NumberFormatException exception) {
                plugin.messages().send(player, "invalid-price", "&cInvalid price.");
                return true;
            }

            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            auctionService.createAuction(player, itemInHand, price).thenAccept(result -> plugin.schedulerAdapter().runEntity(player, () -> {
                plugin.messages().sendRaw(player, result.message());
                if (result.success()) {
                    guiManager.openPlayerItems(player);
                }
            }));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("donutcore.auction.admin")) {
                plugin.messages().send(player, "no-permission-reload", "&cYou do not have permission to reload this plugin.");
                return true;
            }

            plugin.reloadPluginConfiguration();
            plugin.messages().send(player, "config-reloaded", "&aDonutAuctionHouse configuration reloaded.");
            return true;
        }

        if (args[0].equalsIgnoreCase("cancel")) {
            guiManager.openPlayerItems(player);
            return true;
        }

        guiManager.applySearch(player, String.join(" ", args).trim());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("sell", "cancel", "reload").stream().filter(value -> value.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
