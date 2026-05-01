package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.AuctionListing;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AuctionRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<List<AuctionListing>> loadAll();

    CompletableFuture<Void> save(AuctionListing listing);

    CompletableFuture<Void> update(AuctionListing listing);

    CompletableFuture<Void> delete(UUID auctionId);

    CompletableFuture<Optional<AuctionListing>> findById(UUID auctionId);

    CompletableFuture<List<AuctionListing>> findBySeller(UUID sellerId);

    CompletableFuture<List<AuctionListing>> findExpiredActive(long cutoffTime);
}
