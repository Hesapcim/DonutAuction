package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionPage;
import io.nightbeam.donutauction.model.PlayerAuctionSession;
import io.nightbeam.donutauction.service.ActionResult;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.TimeUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

public final class AuctionGui extends BaseGui {

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final DonutCoreHook donutCoreHook;
    private final PlayerAuctionSession session;
    private final Map<Integer, AuctionListing> slotMappings = new HashMap<>();

    public AuctionGui(GuiManager guiManager, AuctionService auctionService, DonutCoreHook donutCoreHook, PlayerAuctionSession session) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.donutCoreHook = donutCoreHook;
        this.session = session;
    }

    @Override
    public Inventory render(Player player) {
        MessageUtil text = guiManager.plugin().auctionGuiText();
        Inventory inventory = attach(Bukkit.createInventory(this, 54, text.component("title", "&6Auction")));
        slotMappings.clear();

        AuctionBrowseRequest request = session.request();
        AuctionPage page = auctionService.browse(request);
        List<AuctionListing> listings = page.listings();
        long now = System.currentTimeMillis();

        for (int slot = 0; slot < Math.min(45, listings.size()); slot++) {
            AuctionListing listing = listings.get(slot);
            inventory.setItem(slot, buildListingItem(player, listing, now));
            slotMappings.put(slot, listing);
        }

        inventory.setItem(47, ItemBuilder.of(Material.CAULDRON)
                .name(text.component("items.sort.name", "&fPrice Sort"))
                .lore(text.components(
                        "items.sort.lore",
                        List.of("&7Current: %sort_mode%", "&8Click to cycle sorting"),
                        MessageUtil.placeholder("sort_mode", sortModeName(request.sortMode().name()))
                ))
                .build());

        inventory.setItem(48, ItemBuilder.of(Material.HOPPER)
                .name(text.component("items.filter.name", "&fFilter"))
                .lore(text.components(
                        "items.filter.lore",
                        List.of("&7Current: %filter_category%", "&8Click to change category"),
                        MessageUtil.placeholder("filter_category", filterCategoryName(request.filterCategory().name()))
                ))
                .build());

        inventory.setItem(49, ItemBuilder.of(Material.ANVIL)
                .name(text.component("items.refresh.name", "&fAuction"))
                .lore(text.components("items.refresh.lore", List.of("&7Refresh the auction house")))
                .build());

        inventory.setItem(50, ItemBuilder.of(Material.OAK_SIGN)
                .name(text.component("items.search.name", "&fSearch"))
                .lore(text.components(
                        "items.search.lore",
                        List.of("&7Current: %search_term%", "&8Type an item name in chat"),
                        MessageUtil.placeholder("search_term", request.searchTerm().isBlank()
                                ? text.text("text.none", "none")
                                : request.searchTerm())
                ))
                .build());

        inventory.setItem(51, ItemBuilder.of(Material.CHEST)
                .name(text.component("items.player-items.name", "&fYour Items"))
                .lore(text.components("items.player-items.lore", List.of("&7View active, sold, and expired listings")))
                .build());

        inventory.setItem(53, ItemBuilder.of(Material.ARROW)
                .name(text.component("items.next-page.name", "&fNext Page"))
                .lore(text.components(
                        page.hasNextPage() ? "items.next-page.lore.available" : "items.next-page.lore.unavailable",
                        List.of(page.hasNextPage() ? "&7Open the next page" : "&7No more listings")
                ))
                .build());

        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slotMappings.containsKey(slot)) {
            AuctionListing listing = slotMappings.get(slot);
            if (event.getClick() == ClickType.MIDDLE) {
                if (player.hasPermission("donutauction.admin")) {
                    guiManager.openAdminRemovalMenu(player, session, listing);
                }
                return;
            }
            auctionService.purchaseAuction(player, listing.auctionId()).thenAccept(result -> sendAndRefresh(player, result));
            return;
        }

        AuctionBrowseRequest request = session.request();
        if (slot == 47) {
            session.request(request.withSortMode(request.sortMode().next()));
            guiManager.refreshAuctionHouse(player);
            return;
        }

        if (slot == 48) {
            guiManager.openFilterMenu(player);
            return;
        }

        if (slot == 49) {
            guiManager.refreshAuctionHouse(player);
            return;
        }

        if (slot == 50) {
            guiManager.beginSearch(player);
            return;
        }

        if (slot == 51) {
            guiManager.openPlayerItems(player);
            return;
        }

        if (slot == 53 && auctionService.browse(request).hasNextPage()) {
            session.request(request.withPage(request.page() + 1));
            guiManager.refreshAuctionHouse(player);
        }
    }

    private ItemStack buildListingItem(Player viewer, AuctionListing listing, long now) {
        MessageUtil text = guiManager.plugin().auctionGuiText();
        ItemStack display = listing.item().clone();
        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.seller());
        String sellerName;
        if (seller.isOnline() && seller.getPlayer() != null) {
            sellerName = donutCoreHook.resolveDisplayName(seller.getPlayer());
        } else {
            sellerName = seller.getName() == null ? text.text("text.unknown-seller", "Unknown") : seller.getName();
        }
        display.editMeta(meta -> {
            String path = viewer.hasPermission("donutauction.admin") ? "listing.admin-lore" : "listing.lore";
            List<String> fallbackLore = viewer.hasPermission("donutauction.admin")
                    ? List.of("&7Price: %price%", "&7Seller: %seller%", "&7Expires in: %time_left%", "&aClick to purchase.", "&cMiddle-click to manage this listing.")
                    : List.of("&7Price: %price%", "&7Seller: %seller%", "&7Expires in: %time_left%", "&aClick to purchase.");
            meta.lore(text.components(path, fallbackLore,
                    MessageUtil.placeholder("price", auctionService.formatPrice(listing.price())),
                    MessageUtil.placeholder("seller", sellerName),
                    MessageUtil.placeholder("time_left", TimeUtil.formatDuration(Math.max(0L, listing.expirationTime() - now)))
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        });
        return display;
    }

    private String sortModeName(String sortModeKey) {
        return guiManager.plugin().auctionGuiText().text("sort-modes." + sortModeKey, switch (sortModeKey) {
            case "HIGHEST_PRICE" -> "Highest Price";
            case "LOWEST_PRICE" -> "Lowest Price";
            case "LATEST" -> "Latest";
            case "RECENTLY_LISTED" -> "Recently Listed";
            default -> sortModeKey;
        });
    }

    private String filterCategoryName(String categoryKey) {
        return guiManager.plugin().auctionGuiText().text("filter-categories." + categoryKey, switch (categoryKey) {
            case "ALL" -> "All";
            case "BLOCKS" -> "Blocks";
            case "TOOLS" -> "Tools";
            case "FOOD" -> "Food";
            case "COMBAT" -> "Combat";
            case "POTIONS" -> "Potions";
            case "BOOKS" -> "Books";
            case "INGREDIENTS" -> "Ingredients";
            case "UTILITIES" -> "Utilities";
            default -> categoryKey;
        });
    }

    private void sendAndRefresh(Player player, ActionResult result) {
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
            guiManager.plugin().messages().sendRaw(player, result.message());
            guiManager.refreshAuctionHouse(player);
        });
    }
}
