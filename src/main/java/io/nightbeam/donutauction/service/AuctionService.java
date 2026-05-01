package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.economy.VaultEconomyProvider;
import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionPage;
import io.nightbeam.donutauction.model.AuctionStatus;
import io.nightbeam.donutauction.model.ListingPriceValidationResult;
import io.nightbeam.donutauction.storage.AuctionRepository;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.SchedulerAdapter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class AuctionService {

    private final AuctionHousePlugin plugin;
    private final SchedulerAdapter schedulerAdapter;
    private final VaultEconomyProvider economyProvider;
    private final AuctionRepository repository;
    private final AuctionManager auctionManager;
    private final DonutCoreHook donutCoreHook;
    private final Map<UUID, AtomicBoolean> operationLocks = new ConcurrentHashMap<>();

    private ScheduledTask expiryTask;

    public AuctionService(
            AuctionHousePlugin plugin,
            SchedulerAdapter schedulerAdapter,
            VaultEconomyProvider economyProvider,
            AuctionRepository repository,
            AuctionManager auctionManager,
            DonutCoreHook donutCoreHook
    ) {
        this.plugin = plugin;
        this.schedulerAdapter = schedulerAdapter;
        this.economyProvider = economyProvider;
        this.repository = repository;
        this.auctionManager = auctionManager;
        this.donutCoreHook = donutCoreHook;
    }

    public void initialize() {
        repository.initialize()
                .thenCompose(ignored -> repository.loadAll())
                .thenAccept(auctionManager::replaceAll)
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to initialize auction storage: " + throwable.getMessage());
                    throwable.printStackTrace();
                    return null;
                });

        long intervalSeconds = plugin.getConfig().getLong("auction.expired-scan-interval-seconds", 60L);
        long intervalTicks = Math.max(20L, intervalSeconds * 20L);
        this.expiryTask = schedulerAdapter.runGlobalRepeating(this::scanAndExpireAuctions, intervalTicks, intervalTicks);
    }

    public void shutdown() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        schedulerAdapter.shutdown();
    }

    public AuctionPage browse(AuctionBrowseRequest request) {
        return auctionManager.browse(request, System.currentTimeMillis());
    }

    public List<AuctionListing> getPlayerAuctions(UUID playerId) {
        return auctionManager.sellerListings(playerId);
    }

    public CompletableFuture<ActionResult> createAuction(Player player, ItemStack itemInHand, double price) {
        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            return CompletableFuture.completedFuture(ActionResult.failure(message("hold-item", "&cHold the item you want to list.")));
        }

        if (plugin.isBlockedMaterial(itemInHand.getType())) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "blocked-material",
                    "&cYou cannot list &6%material%&c on the auction house.",
                    MessageUtil.placeholder("material", materialName(itemInHand.getType()))
            )));
        }

        double minPrice = plugin.getConfig().getDouble("auction.min-price", 10.0D);
        double maxPrice = plugin.getConfig().getDouble("auction.max-price", 1.0E9);
        ListingPriceValidationResult validationResult = ListingPriceValidationResult.validate(price, minPrice, maxPrice);
        if (validationResult == ListingPriceValidationResult.BELOW_MINIMUM) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "price-below-min",
                    "&cMinimum auction price is &6%min_price%&c.",
                    MessageUtil.placeholder("min_price", economyProvider.format(Math.max(0.0D, minPrice)))
            )));
        }
        if (validationResult == ListingPriceValidationResult.INVALID_OR_ABOVE_MAX) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "price-invalid",
                    "&cPrice must be greater than 0 and below the configured limit."
            )));
        }

        long now = System.currentTimeMillis();
        long duration = plugin.getConfig().getLong("auction.listing-duration-hours", 48L) * 3_600_000L;
        AuctionListing listing = new AuctionListing(UUID.randomUUID(), itemInHand.clone(), player.getUniqueId(), price, now, now + duration, AuctionStatus.ACTIVE, null, 0L, false);

        removeHeldItem(player);
        return repository.save(listing)
                .thenApply(ignored -> {
                    auctionManager.upsert(listing);
                    return ActionResult.success(message(
                            "auction-created",
                            "&aListed &6%item%&a for &6%price%&a.",
                            MessageUtil.placeholder("item", itemName(itemInHand)),
                            MessageUtil.placeholder("price", economyProvider.format(price))
                    ));
                })
                .exceptionally(throwable -> {
                    schedulerAdapter.runEntity(player, () -> restoreItem(player, itemInHand));
                    plugin.getLogger().severe("Failed to save auction listing: " + throwable.getMessage());
                    return ActionResult.failure(message(
                            "auction-create-failed",
                            "&cFailed to create the auction. Your item was returned."
                    ));
                });
    }

    public CompletableFuture<ActionResult> purchaseAuction(Player buyer, UUID auctionId) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "auction-unavailable",
                    "&cThat auction is no longer available."
            )));
        }
        if (listing.seller().equals(buyer.getUniqueId())) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "auction-own",
                    "&cYou cannot buy your own auction."
            )));
        }

        long now = System.currentTimeMillis();
        if (!listing.isActive(now)) {
            expireListingIfNeeded(listing);
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "auction-expired",
                    "&cThat auction has expired."
            )));
        }

        AtomicBoolean operationLock = operationLocks.computeIfAbsent(auctionId, ignored -> new AtomicBoolean());
        if (!operationLock.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "auction-processing",
                    "&cThat auction is already being processed."
            )));
        }

        OfflinePlayer offlineBuyer = Bukkit.getOfflinePlayer(buyer.getUniqueId());
        if (!economyProvider.has(offlineBuyer, listing.price())) {
            operationLock.set(false);
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "insufficient-funds",
                    "&cYou do not have enough money."
            )));
        }

        EconomyResponse withdrawal = economyProvider.withdraw(offlineBuyer, listing.price());
        if (!withdrawal.transactionSuccess()) {
            operationLock.set(false);
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "withdraw-failed",
                    "&cUnable to withdraw funds: &6%reason%",
                    MessageUtil.placeholder("reason", withdrawal.errorMessage)
            )));
        }

        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.seller());
        EconomyResponse deposit = economyProvider.deposit(seller, listing.price());
        if (!deposit.transactionSuccess()) {
            economyProvider.deposit(offlineBuyer, listing.price());
            operationLock.set(false);
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "seller-payment-failed",
                    "&cUnable to pay the seller right now."
            )));
        }

        AuctionListing soldListing = listing.asSold(buyer.getUniqueId(), now);
        auctionManager.upsert(soldListing);
        deliverItem(buyer, soldListing.item());

        return repository.update(soldListing)
                .thenApply(ignored -> ActionResult.success(message(
                        "auction-purchased",
                        "&aPurchased &6%item%&a for &6%price%&a.",
                        MessageUtil.placeholder("item", itemName(soldListing.item())),
                        MessageUtil.placeholder("price", economyProvider.format(soldListing.price()))
                )))
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to persist auction purchase: " + throwable.getMessage());
                    return ActionResult.success(message(
                            "purchase-persist-warning",
                            "&ePurchase completed, but persistence reported an error. Check console."
                    ));
                })
                .whenComplete((result, throwable) -> operationLock.set(false));
    }

    public CompletableFuture<ActionResult> cancelAuction(Player seller, UUID auctionId) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null || !listing.seller().equals(seller.getUniqueId())) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "auction-not-found",
                    "&cAuction not found."
            )));
        }
        if (listing.status() != AuctionStatus.ACTIVE) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "cancel-only-active",
                    "&cOnly active auctions can be cancelled."
            )));
        }

        AuctionListing cancelled = listing.withStatus(AuctionStatus.CANCELLED).markSellerClaimed();
        auctionManager.upsert(cancelled);
        restoreItem(seller, cancelled.item());
        return repository.update(cancelled)
                .thenApply(ignored -> ActionResult.success(message(
                        "auction-cancelled",
                        "&aAuction cancelled and item returned."
                )))
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to persist cancellation: " + throwable.getMessage());
                    return ActionResult.success(message(
                            "cancel-persist-warning",
                            "&eAuction cancelled in memory, but persistence reported an error. Check console."
                    ));
                });
    }

    public CompletableFuture<ActionResult> adminRemoveAuction(Player admin, UUID auctionId, boolean refundSeller, boolean notifySeller) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "admin-remove-not-found",
                    "&cThat auction is no longer available."
            )));
        }
        if (listing.status() != AuctionStatus.ACTIVE) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "admin-remove-not-active",
                    "&cOnly active listings can be removed from the auction browser."
            )));
        }

        AtomicBoolean operationLock = operationLocks.computeIfAbsent(auctionId, ignored -> new AtomicBoolean());
        if (!operationLock.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "auction-processing",
                    "&cThat auction is already being processed."
            )));
        }

        if (refundSeller) {
            AuctionListing returnedListing = listing.withStatus(AuctionStatus.CANCELLED);
            return repository.update(returnedListing)
                .thenApply(ignored -> {
                    auctionManager.upsert(returnedListing);
                    if (notifySeller) {
                        notifySellerOfAdminRemoval(admin, listing, true);
                    }
                    return ActionResult.success(message(
                            "admin-remove-refunded",
                            "&aRemoved &6%item%&a from the auction house and returned it to the seller claim menu.",
                            MessageUtil.placeholder("item", itemName(listing.item()))
                    ));
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to return auction listing to seller after admin removal: " + throwable.getMessage());
                    return ActionResult.failure(message(
                            "admin-remove-failed",
                            "&cFailed to remove the listing. Check console for details."
                    ));
                })
                .whenComplete((result, throwable) -> operationLock.set(false));
        }

        return repository.delete(auctionId)
                .thenApply(ignored -> {
                    auctionManager.remove(auctionId);
                    if (notifySeller) {
                        notifySellerOfAdminRemoval(admin, listing, false);
                    }
                    return ActionResult.success(message(
                            "admin-remove-no-refund",
                            "&aRemoved &6%item%&a from the auction house without a refund.",
                            MessageUtil.placeholder("item", itemName(listing.item()))
                    ));
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("Failed to remove auction listing by admin: " + throwable.getMessage());
                    return ActionResult.failure(message(
                            "admin-remove-failed",
                            "&cFailed to remove the listing. Check console for details."
                    ));
                })
                .whenComplete((result, throwable) -> operationLock.set(false));
    }

    public CompletableFuture<ActionResult> collectSellerProceeds(Player seller, UUID auctionId) {
        AuctionListing listing = auctionManager.findCached(auctionId);
        if (listing == null || !listing.seller().equals(seller.getUniqueId())) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "auction-not-found",
                    "&cAuction not found."
            )));
        }
        if (listing.sellerClaimed()) {
            return CompletableFuture.completedFuture(ActionResult.failure(message(
                    "already-collected",
                    "&cThat auction has already been collected."
            )));
        }

        if (listing.status() == AuctionStatus.SOLD && !listing.sellerClaimed()) {
            AuctionListing claimed = listing.markSellerClaimed();
            auctionManager.upsert(claimed);
            return repository.update(claimed)
                    .thenApply(ignored -> ActionResult.success(message(
                            "sale-collected",
                            "&aSale marked as collected. Payment was delivered through Vault at purchase time."
                    )))
                    .exceptionally(throwable -> ActionResult.failure(message(
                            "collection-update-failed",
                            "&cUnable to update the collection state."
                    )));
        }

        if (listing.status() == AuctionStatus.EXPIRED || listing.status() == AuctionStatus.CANCELLED) {
            restoreItem(seller, listing.item());
            AuctionListing collected = listing.markSellerClaimed();
            auctionManager.upsert(collected);
            return repository.update(collected)
                    .thenApply(ignored -> ActionResult.success(message(
                            "item-returned",
                            "&aReturned your unsold item."
                    )))
                    .exceptionally(throwable -> ActionResult.failure(message(
                            "collection-update-failed",
                            "&cUnable to update the collection state."
                    )));
        }

        return CompletableFuture.completedFuture(ActionResult.failure(message(
                "nothing-to-collect",
                "&cNothing to collect for that auction."
        )));
    }

    public Optional<AuctionListing> findListing(UUID auctionId) {
        return Optional.ofNullable(auctionManager.findCached(auctionId));
    }

    public DonutCoreHook donutCoreHook() {
        return donutCoreHook;
    }

    public String formatPrice(double price) {
        return economyProvider.format(price);
    }

    private void scanAndExpireAuctions() {
        long now = System.currentTimeMillis();
        repository.findExpiredActive(now).thenAccept(listings -> {
            for (AuctionListing listing : listings) {
                AuctionListing expired = listing.asExpired();
                auctionManager.upsert(expired);
                repository.update(expired);
            }
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to scan expired auctions: " + throwable.getMessage());
            return null;
        });
    }

    private void expireListingIfNeeded(AuctionListing listing) {
        if (listing.status() != AuctionStatus.ACTIVE) {
            return;
        }
        AuctionListing expired = listing.asExpired();
        auctionManager.upsert(expired);
        repository.update(expired);
    }

    private void removeHeldItem(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.setItemInMainHand(new ItemStack(Material.AIR));
    }

    private void restoreItem(Player player, ItemStack itemStack) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack.clone());
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void deliverItem(Player player, ItemStack itemStack) {
        restoreItem(player, itemStack);
    }

    private String itemName(ItemStack itemStack) {
        if (itemStack.hasItemMeta() && itemStack.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(itemStack.getItemMeta().displayName());
        }
        return materialName(itemStack.getType());
    }

    private String materialName(Material material) {
        StringBuilder builder = new StringBuilder();
        for (String part : material.name().toLowerCase(Locale.ENGLISH).split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private String message(String path, String fallback, MessageUtil.Placeholder... placeholders) {
        return plugin.messages().text(path, fallback, placeholders);
    }

    private void notifySellerOfAdminRemoval(Player admin, AuctionListing listing, boolean refunded) {
        Player seller = Bukkit.getPlayer(listing.seller());
        if (seller == null) {
            return;
        }

        String path = refunded ? "seller-admin-remove-refunded" : "seller-admin-remove-no-refund";
        String fallback = refunded
                ? "&eYour listing for &6%item%&e was removed by &6%admin%&e. You can reclaim the item from Your Items."
                : "&eYour listing for &6%item%&e was removed by &6%admin%&e without a refund.";
        schedulerAdapter.runEntity(seller, () -> plugin.messages().sendRaw(seller, message(
                path,
                fallback,
                MessageUtil.placeholder("item", itemName(listing.item())),
                MessageUtil.placeholder("admin", donutCoreHook.resolveDisplayName(admin))
        )));
    }
}
