package com.reazip.economycraft.stocks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.reazip.economycraft.EconomyManager;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class StockManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DEFAULT_RESOURCE_PATH = "/assets/economycraft/stocks.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final MinecraftServer server;
    private final Path file;
    private final Path holdingsFile;
    private final Map<String, StockEntry> stocks = new LinkedHashMap<>();
    private final Map<UUID, Map<String, StockHolding>> holdings = new HashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<String, Long> netVolume = new HashMap<>();
    private final Map<UUID, Long> lastTrade = new HashMap<>();

    private java.util.concurrent.ScheduledExecutorService scheduler;
    private long tickIntervalMs = 1000L; // will be initialized from config

    public enum TradeResult {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        COOLDOWN,
        INSUFFICIENT_HOLDINGS,
        INVALID_STOCK,
        OVERSIZED,
        ERROR
    }

    public StockManager(MinecraftServer server) {
        this.server = server;
        Path dir = server.getFile("config/economycraft");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}

        this.file = dir.resolve("stocks.json");
        this.holdingsFile = dir.resolve("holdings.json");

        if (Files.notExists(this.file)) {
            createFromBundledDefault();
        }

        // initialize from config (loaded by EconomyCraft during server start)
        try {
            this.tickIntervalMs = com.reazip.economycraft.EconomyConfig.get().stockTickIntervalMs;
        } catch (Exception ignored) {}

        reload();
        loadHoldings();
        startSimulation();
    }

    private void loadHoldings() {
        holdings.clear();
        if (Files.notExists(holdingsFile)) return;
        try {
            String json = Files.readString(holdingsFile, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                try {
                    UUID player = UUID.fromString(e.getKey());
                    JsonObject mapObj = e.getValue().getAsJsonObject();
                    Map<String, StockHolding> m = new HashMap<>();
                    for (Map.Entry<String, JsonElement> f : mapObj.entrySet()) {
                        String stockId = f.getKey();
                        StockHolding sh = GSON.fromJson(f.getValue(), StockHolding.class);
                        if (sh != null) m.put(stockId, sh);
                    }
                    holdings.put(player, m);
                } catch (Exception ex) {
                    LOGGER.warn("Failed to parse holdings entry {}", e.getKey(), ex);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to load holdings from {}", holdingsFile, ex);
        }
    }


    private static final Map<MinecraftServer, StockManager> INSTANCES = new HashMap<>();

    public static synchronized StockManager get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, k -> new StockManager(server));
    }

    private void createFromBundledDefault() {
        try (InputStream in = StockManager.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) {
                LOGGER.warn("Default stocks resource not found at {}. Creating empty {}", DEFAULT_RESOURCE_PATH, file);
                Files.writeString(file, "{}", StandardCharsets.UTF_8);
                return;
            }
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Created {} from bundled default {}", file, DEFAULT_RESOURCE_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to create stocks.json at {}", file, e);
        }
    }

    public synchronized void reload() {
        stocks.clear();
        if (Files.notExists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return;

            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                String key = e.getKey();
                JsonObject obj = e.getValue().getAsJsonObject();
                double price = obj.has("price") ? obj.get("price").getAsDouble() : 1.0;
                double vol = obj.has("volatility") ? obj.get("volatility").getAsDouble() : 0.01;
                double liq = obj.has("liquidity") ? obj.get("liquidity").getAsDouble() : 1000.0;
                int historySize = obj.has("historySize") ? obj.get("historySize").getAsInt() : 9;
                String name = obj.has("name") ? obj.get("name").getAsString() : key;
                StockEntry se = new StockEntry(key, name, price, vol, liq, historySize);
                stocks.put(key, se);
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to load stocks.json from {}", file, ex);
        }
    }

    public synchronized Collection<StockEntry> getAll() {
        return Collections.unmodifiableCollection(stocks.values());
    }

    public synchronized StockEntry get(String id) {
        return stocks.get(id);
    }

    public synchronized Map<String, StockHolding> getHoldings(UUID player) {
        return holdings.getOrDefault(player, Collections.emptyMap());
    }

    public synchronized TradeResult buy(UUID player, String stockId, long qty) {
        StockEntry s = stocks.get(stockId);
        if (s == null || qty <= 0) return TradeResult.INVALID_STOCK;
        long now = System.currentTimeMillis();
        long cooldown = com.reazip.economycraft.EconomyConfig.get().stockTradeCooldownMs;
        Long last = lastTrade.get(player);
        if (last != null && now - last < cooldown) return TradeResult.COOLDOWN;
        long maxQty = com.reazip.economycraft.EconomyConfig.get().stockMaxTradeQty;
        if (qty > maxQty) return TradeResult.OVERSIZED;
        EconomyManager manager = com.reazip.economycraft.EconomyCraft.getManager(server);
        long cost = Math.round(s.price * qty);
        if (!manager.removeMoney(player, cost)) return TradeResult.INSUFFICIENT_FUNDS;

        Map<String, StockHolding> m = holdings.computeIfAbsent(player, k -> new HashMap<>());
        StockHolding h = m.get(stockId);
        if (h == null) {
            m.put(stockId, new StockHolding(qty, s.price));
        } else {
            long newQty = h.quantity + qty;
            double newAvg = ((h.avgCost * h.quantity) + (s.price * qty)) / newQty;
            h.quantity = newQty;
            h.avgCost = newAvg;
        }
        notifyListeners();
        save();
        netVolume.put(stockId, netVolume.getOrDefault(stockId, 0L) + qty);
        lastTrade.put(player, now);
        return TradeResult.SUCCESS;
    }

    public synchronized TradeResult sell(UUID player, String stockId, long qty) {
        StockEntry s = stocks.get(stockId);
        if (s == null || qty <= 0) return TradeResult.INVALID_STOCK;
        long now = System.currentTimeMillis();
        long cooldown = com.reazip.economycraft.EconomyConfig.get().stockTradeCooldownMs;
        Long last = lastTrade.get(player);
        if (last != null && now - last < cooldown) return TradeResult.COOLDOWN;
        Map<String, StockHolding> m = holdings.get(player);
        if (m == null) return TradeResult.INSUFFICIENT_HOLDINGS;
        StockHolding h = m.get(stockId);
        if (h == null || h.quantity < qty) return TradeResult.INSUFFICIENT_HOLDINGS;

        long proceeds = Math.round(s.price * qty);
        h.quantity -= qty;
        if (h.quantity <= 0) m.remove(stockId);

        EconomyManager manager = com.reazip.economycraft.EconomyCraft.getManager(server);
        manager.addMoney(player, proceeds);
        notifyListeners();
        save();
        netVolume.put(stockId, netVolume.getOrDefault(stockId, 0L) - qty);
        lastTrade.put(player, now);
        return TradeResult.SUCCESS;
    }

    private void startSimulation() {
        if (scheduler != null) return;
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "economycraft-stocks-sim");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // run simulation on server thread
                server.execute(this::simulateTick);
            } catch (Exception ex) {
                LOGGER.warn("Failed to schedule stock simulateTick", ex);
            }
        }, tickIntervalMs, tickIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void stopSimulation() {
        if (scheduler != null) {
            try { scheduler.shutdownNow(); } catch (Exception ignored) {}
            scheduler = null;
        }
    }

    private synchronized void simulateTick() {
        // Simple simulation: apply netVolume impact and random noise
        for (Map.Entry<String, StockEntry> e : stocks.entrySet()) {
            String id = e.getKey();
            StockEntry s = e.getValue();
            long vol = netVolume.getOrDefault(id, 0L);
            // volume impact proportional to qty / liquidity
            double impact = (vol / (s.liquidity + 1.0)) * 0.01; // scale
            double noise = (Math.random() - 0.5) * s.volatility * s.price;
            double newPrice = s.price * (1.0 + impact) + noise;
            // clamp to 1 cent minimum and limit per-tick move to 20%
            double maxMove = s.price * 0.20;
            double delta = Math.max(-maxMove, Math.min(maxMove, newPrice - s.price));
            s.price = Math.max(0.01, s.price + delta);
            s.appendHistory(s.price);
        }
        netVolume.clear();
        notifyListeners();
        save();
    }

    public synchronized void addListener(Runnable r) { listeners.add(r); }
    public synchronized void removeListener(Runnable r) { listeners.remove(r); }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, StockEntry> e : stocks.entrySet()) {
                StockEntry s = e.getValue();
                JsonObject obj = new JsonObject();
                obj.addProperty("name", s.name);
                obj.addProperty("price", s.price);
                obj.addProperty("volatility", s.volatility);
                obj.addProperty("liquidity", s.liquidity);
                obj.addProperty("historySize", s.history == null ? 0 : s.history.length);
                root.add(e.getKey(), obj);
            }
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException ignored) {}

        // save holdings
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Map<String, StockHolding>> p : holdings.entrySet()) {
                JsonObject mapObj = new JsonObject();
                for (Map.Entry<String, StockHolding> h : p.getValue().entrySet()) {
                    mapObj.add(h.getKey(), GSON.toJsonTree(h.getValue()));
                }
                root.add(p.getKey().toString(), mapObj);
            }
            Files.writeString(holdingsFile, GSON.toJson(root));
        } catch (IOException ex) {
            LOGGER.error("Failed to save holdings to {}", holdingsFile, ex);
        }
    }

    public synchronized void addHolding(UUID player, String stockId, long qty, double avgCost) {
        if (qty <= 0) return;
        Map<String, StockHolding> m = holdings.computeIfAbsent(player, k -> new HashMap<>());
        StockHolding h = m.get(stockId);
        if (h == null) m.put(stockId, new StockHolding(qty, avgCost));
        else {
            long newQty = h.quantity + qty;
            double newAvg = ((h.avgCost * h.quantity) + (avgCost * qty)) / newQty;
            h.quantity = newQty;
            h.avgCost = newAvg;
        }
        save();
    }

    public synchronized void setPrice(String stockId, double price) {
        StockEntry s = stocks.get(stockId);
        if (s == null) return;
        s.price = Math.max(0.01, price);
        s.appendHistory(s.price);
        save();
        notifyListeners();
    }

}
