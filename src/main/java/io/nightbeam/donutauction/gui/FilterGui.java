package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.PlayerAuctionSession;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.MessageUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

public final class FilterGui extends BaseGui {

    private final GuiManager guiManager;
    private final PlayerAuctionSession session;
    private final Map<Integer, AuctionFilterCategory> categories = new HashMap<>();

    public FilterGui(GuiManager guiManager, PlayerAuctionSession session) {
        this.guiManager = guiManager;
        this.session = session;
    }

    @Override
    public Inventory render(Player player) {
        MessageUtil text = guiManager.plugin().filterGuiText();
        Inventory inventory = attach(Bukkit.createInventory(this, 27, text.component("title", "&6Auction Filter")));
        categories.clear();

        AuctionFilterCategory[] values = {
                AuctionFilterCategory.BLOCKS,
                AuctionFilterCategory.TOOLS,
                AuctionFilterCategory.FOOD,
                AuctionFilterCategory.COMBAT,
                AuctionFilterCategory.POTIONS,
                AuctionFilterCategory.BOOKS,
                AuctionFilterCategory.INGREDIENTS,
                AuctionFilterCategory.UTILITIES
        };

        for (int index = 0; index < values.length; index++) {
            AuctionFilterCategory category = values[index];
            inventory.setItem(index, ItemBuilder.of(category.icon())
                    .name(text.component("categories." + category.name() + ".name", "&f" + category.displayName()))
                    .lore(text.components("categories." + category.name() + ".lore", List.of("&7Filter auction listings")))
                    .build());
            categories.put(index, category);
        }

        inventory.setItem(22, ItemBuilder.of(Material.BARRIER)
                .name(text.component("clear.name", "&cClear Filter"))
                .lore(text.components("clear.lore", List.of("&7Show all listings")))
                .build());
        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == 22) {
            session.request(session.request().withFilter(AuctionFilterCategory.ALL));
            guiManager.openAuctionHouse(player, session);
            return;
        }

        AuctionFilterCategory category = categories.get(slot);
        if (category != null) {
            session.request(session.request().withFilter(category));
            guiManager.openAuctionHouse(player, session);
        }
    }
}
