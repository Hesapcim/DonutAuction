package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import io.nightbeam.donutauction.service.ActionResult;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.TimeUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class PlayerAuctionGui extends BaseGui {

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final Map<Integer, UUID> slotMappings = new HashMap<>();
    private int page;

    public PlayerAuctionGui(GuiManager guiManager, AuctionService auctionService, int page) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.page = Math.max(1, page);
    }

    @Override
    public Inventory render(Player player) {
        MessageUtil text = guiManager.plugin().playerAuctionsGuiText();
        Inventory inventory = attach(Bukkit.createInventory(this, 54, text.component("title", "&6Your Items")));
        slotMappings.clear();

        List<AuctionListing> listings = auctionService.getPlayerAuctions(player.getUniqueId());
        int totalPages = Math.max(1, (int) Math.ceil(listings.size() / 45.0D));
        page = Math.min(page, totalPages);
        guiManager.setPlayerItemsPage(player.getUniqueId(), page);
        int from = (page - 1) * 45;
        int to = Math.min(listings.size(), from + 45);
        long now = System.currentTimeMillis();

        for (int slot = 0; slot < to - from; slot++) {
            AuctionListing listing = listings.get(from + slot);
            inventory.setItem(slot, buildItem(listing, now));
            slotMappings.put(slot, listing.auctionId());
        }

        inventory.setItem(45, ItemBuilder.of(Material.ARROW)
                .name(text.component("items.previous-page.name", "&fPrevious Page"))
                .lore(text.components(
                        page > 1 ? "items.previous-page.lore.available" : "items.previous-page.lore.unavailable",
                        List.of(page > 1 ? "&7Go back" : "&7No previous page")
                ))
                .build());
        inventory.setItem(49, ItemBuilder.of(Material.CHEST)
                .name(text.component("items.back.name", "&fBack to Auction"))
                .lore(text.components("items.back.lore", List.of("&7Return to the auction browser")))
                .build());
        inventory.setItem(53, ItemBuilder.of(Material.ARROW)
                .name(text.component("items.next-page.name", "&fNext Page"))
                .lore(text.components(
                        page < totalPages ? "items.next-page.lore.available" : "items.next-page.lore.unavailable",
                        List.of(page < totalPages ? "&7Open next page" : "&7No more listings")
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
            UUID auctionId = slotMappings.get(slot);
            auctionService.findListing(auctionId).ifPresent(listing -> {
                if (listing.status() == AuctionStatus.ACTIVE) {
                    auctionService.cancelAuction(player, auctionId).thenAccept(result -> sendAndRefresh(player, result));
                } else {
                    auctionService.collectSellerProceeds(player, auctionId).thenAccept(result -> sendAndRefresh(player, result));
                }
            });
            return;
        }

        if (slot == 45 && page > 1) {
            page--;
            guiManager.setPlayerItemsPage(player.getUniqueId(), page);
            guiManager.openPlayerItems(player);
            return;
        }
        if (slot == 49) {
            guiManager.openAuctionHouse(player);
            return;
        }
        if (slot == 53) {
            page++;
            guiManager.setPlayerItemsPage(player.getUniqueId(), page);
            guiManager.openPlayerItems(player);
        }
    }

    private ItemStack buildItem(AuctionListing listing, long now) {
        MessageUtil text = guiManager.plugin().playerAuctionsGuiText();
        ItemStack display = listing.item().clone();
        display.editMeta(meta -> meta.lore(text.components(
                "listing.lore",
                List.of("&7Price: %price%", "&7Time remaining: %time_remaining%", "%status_line%", "&a%action_line%"),
                MessageUtil.placeholder("price", auctionService.formatPrice(listing.price())),
                MessageUtil.placeholder("time_remaining", TimeUtil.formatDuration(Math.max(0L, listing.expirationTime() - now))),
                MessageUtil.placeholder("status_line", statusLine(listing.status())),
                MessageUtil.placeholder("action_line", actionLine(listing))
        )));
        return display;
    }

    private String statusLine(AuctionStatus status) {
        return guiManager.plugin().playerAuctionsGuiText().text("status-lines." + status.name(), switch (status) {
            case ACTIVE -> "&fStatus: Active";
            case SOLD -> "&aStatus: Sold";
            case EXPIRED -> "&cStatus: Expired";
            case CANCELLED -> "&cStatus: Cancelled";
        });
    }

    private String actionLine(AuctionListing listing) {
        String actionKey = switch (listing.status()) {
            case ACTIVE -> "ACTIVE";
            case SOLD -> listing.sellerClaimed() ? "SOLD_CLAIMED" : "SOLD_UNCLAIMED";
            case EXPIRED -> listing.sellerClaimed() ? "EXPIRED_CLAIMED" : "EXPIRED_UNCLAIMED";
            case CANCELLED -> listing.sellerClaimed() ? "CANCELLED_CLAIMED" : "CANCELLED_UNCLAIMED";
        };

        return guiManager.plugin().playerAuctionsGuiText().text("action-lines." + actionKey, switch (actionKey) {
            case "ACTIVE" -> "Click to cancel auction.";
            case "SOLD_UNCLAIMED" -> "Click to mark proceeds collected.";
            case "SOLD_CLAIMED", "EXPIRED_CLAIMED", "CANCELLED_CLAIMED" -> "Already collected.";
            case "EXPIRED_UNCLAIMED", "CANCELLED_UNCLAIMED" -> "Click to reclaim item.";
            default -> actionKey;
        });
    }

    private void sendAndRefresh(Player player, ActionResult result) {
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
            guiManager.plugin().messages().sendRaw(player, result.message());
            guiManager.openPlayerItems(player);
        });
    }
}
