package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.PlayerAuctionSession;
import io.nightbeam.donutauction.service.ActionResult;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.TimeUtil;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class AdminRemovalGui extends BaseGui {

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final PlayerAuctionSession session;
    private final AuctionListing listing;
    private final boolean notifySeller;

    public AdminRemovalGui(GuiManager guiManager, AuctionService auctionService, PlayerAuctionSession session, AuctionListing listing, boolean notifySeller) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.session = session;
        this.listing = listing;
        this.notifySeller = notifySeller;
    }

    @Override
    public Inventory render(Player player) {
        MessageUtil text = guiManager.plugin().adminRemovalGuiText();
        Inventory inventory = attach(Bukkit.createInventory(this, 27, text.component("title", "&cAdmin Removal")));

        inventory.setItem(11, ItemBuilder.of(Material.RED_CONCRETE)
                .name(text.component("items.remove-no-refund.name", "&cRemove Without Refund"))
                .lore(text.components("items.remove-no-refund.lore", List.of("&7Delete this listing", "&7Do not refund the seller")))
                .build());

        inventory.setItem(13, previewItem());

        inventory.setItem(15, ItemBuilder.of(Material.EMERALD_BLOCK)
                .name(text.component("items.remove-with-refund.name", "&aRemove With Refund"))
                .lore(text.components("items.remove-with-refund.lore", List.of("&7Remove this listing from the market", "&7Return the item to the seller claim menu")))
                .build());

        inventory.setItem(18, ItemBuilder.of(notifySeller ? Material.BELL : Material.LEVER)
                .name(text.component(
                        notifySeller ? "items.notify.enabled.name" : "items.notify.disabled.name",
                        notifySeller ? "&aNotify Seller: Enabled" : "&cNotify Seller: Disabled"
                ))
                .lore(text.components(
                        notifySeller ? "items.notify.enabled.lore" : "items.notify.disabled.lore",
                        List.of(notifySeller ? "&7Seller will receive a removal message" : "&7Seller will not receive a removal message",
                                "&8Click to toggle notification")
                ))
                .build());

        inventory.setItem(22, ItemBuilder.of(Material.BARRIER)
                .name(text.component("items.cancel.name", "&fKeep Listing"))
                .lore(text.components("items.cancel.lore", List.of("&7Return to the auction browser")))
                .build());

        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == 11) {
            auctionService.adminRemoveAuction(player, listing.auctionId(), false, notifySeller).thenAccept(result -> sendAndReturn(player, result));
            return;
        }
        if (slot == 15) {
            auctionService.adminRemoveAuction(player, listing.auctionId(), true, notifySeller).thenAccept(result -> sendAndReturn(player, result));
            return;
        }
        if (slot == 18) {
            guiManager.openAdminRemovalMenu(player, session, listing, !notifySeller);
            return;
        }
        if (slot == 22) {
            guiManager.openAuctionHouse(player, session);
        }
    }

    private ItemStack previewItem() {
        MessageUtil text = guiManager.plugin().adminRemovalGuiText();
        ItemStack display = listing.item().clone();
        display.editMeta(meta -> meta.lore(text.components(
                "items.preview.lore",
                List.of("&7Seller: %seller%", "&7Price: %price%", "&7Time left: %time_left%", "&8Choose how to remove this listing"),
                MessageUtil.placeholder("seller", sellerName()),
                MessageUtil.placeholder("price", auctionService.formatPrice(listing.price())),
                MessageUtil.placeholder("time_left", TimeUtil.formatDuration(Math.max(0L, listing.expirationTime() - System.currentTimeMillis())))
        )));
        return display;
    }

    private String sellerName() {
        Player seller = Bukkit.getPlayer(listing.seller());
        if (seller != null) {
            return auctionService.donutCoreHook().resolveDisplayName(seller);
        }
        return Bukkit.getOfflinePlayer(listing.seller()).getName() == null
                ? guiManager.plugin().adminRemovalGuiText().text("text.unknown-seller", "Unknown")
                : Bukkit.getOfflinePlayer(listing.seller()).getName();
    }

    private void sendAndReturn(Player player, ActionResult result) {
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
            guiManager.plugin().messages().sendRaw(player, result.message());
            guiManager.openAuctionHouse(player, session);
        });
    }
}
