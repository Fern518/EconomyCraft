package com.reazip.economycraft.stocks;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.MenuProvider;

import java.util.ArrayList;
import java.util.List;

public final class StocksUi {
    private StocksUi() {}

    public static void open(ServerPlayer player) {
        EconomyManager manager = EconomyCraft.getManager(player.level().getServer());
        StockManager stocks = StockManager.get(player.level().getServer());

        Component title = Component.literal("Stocks");
        player.openMenu(new net.minecraft.world.MenuProvider() {
            @Override public Component getDisplayName() { return title; }
            @Override public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inv, net.minecraft.world.entity.player.Player p) {
                return new StocksMenu(id, inv, stocks, player);
            }
        });
    }

    static void openTrade(ServerPlayer player, StockEntry stock) {
        player.openMenu(new net.minecraft.world.MenuProvider() {
            @Override public Component getDisplayName() { return Component.literal("Trade: " + stock.name); }
            @Override public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inv, net.minecraft.world.entity.player.Player p) {
                return new TradeMenu(id, inv, stock, player);
            }
        });
    }

    private static class StocksMenu extends AbstractContainerMenu {
        private final StockManager stocks;
        private final ServerPlayer viewer;
        private final SimpleContainer container = new SimpleContainer(54);

        StocksMenu(int id, Inventory inv, StockManager stocks, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x6, id);
            this.stocks = stocks;
            this.viewer = viewer;

            for (int i = 0; i < 54; i++) {
                int r = i / 9;
                int c = i % 9;
                this.addSlot(new Slot(container, i, 8 + c * 18, 18 + r * 18) {
                    @Override public boolean mayPickup(Player player) { return false; }
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                });
            }

            // Player inventory slots
            int y = 18 + 6 * 18 + 14;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) {
                    this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, y + r * 18));
                }
            }
            for (int c = 0; c < 9; c++) {
                this.addSlot(new Slot(inv, c, 8 + c * 18, y + 58));
            }

            updatePage();
            stocks.addListener(this::updatePage);
        }

        @Override
        public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP && slot < 45) {
                int index = slot;
                var all = new ArrayList<>(stocks.getAll());
                if (index < all.size()) {
                    StockEntry s = all.get(index);
                    StocksUi.openTrade((ServerPlayer) player, s);
                    return;
                }
            }
            super.clicked(slot, dragType, type, player);
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            stocks.removeListener(this::updatePage);
        }

        private void updatePage() {
            var all = new ArrayList<>(stocks.getAll());
            container.clearContent();
            for (int i = 0; i < Math.min(45, all.size()); i++) {
                StockEntry s = all.get(i);
                ItemStack it = new ItemStack(Items.PAPER);
                it.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(s.name));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal("Price: " + EconomyCraft.formatMoney(Math.round(s.price))));
                // sparkline
                double[] h = s.snapshotHistory();
                if (h.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    double min = h[0], max = h[0];
                    for (double d : h) { if (d < min) min = d; if (d > max) max = d; }
                    for (double d : h) {
                        int level = (int) Math.floor(((d - min) / Math.max(1e-9, (max - min))) * 7.0);
                        level = Math.max(0, Math.min(7, level));
                        char[] bars = new char[]{'▁','▂','▃','▄','▅','▆','▇','█'};
                        sb.append(bars[level]);
                    }
                    lore.add(Component.literal("History: " + sb.toString()));
                }
                it.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
                container.setItem(i, it);
            }
        }

        @Override public boolean stillValid(Player pPlayer) { return true; }

        @Override public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) { return net.minecraft.world.item.ItemStack.EMPTY; }
    }

    private static class TradeMenu extends AbstractContainerMenu {
        private final StockEntry stock;
        private final ServerPlayer viewer;
        private final SimpleContainer container = new SimpleContainer(9);

        TradeMenu(int id, Inventory inv, StockEntry stock, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x1, id);
            this.stock = stock;
            this.viewer = viewer;

            ItemStack buy1 = new ItemStack(Items.LIME_STAINED_GLASS_PANE);
            buy1.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Buy x1").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GREEN)));
            container.setItem(1, buy1);

            ItemStack buy10 = new ItemStack(Items.LIME_STAINED_GLASS_PANE);
            buy10.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Buy x10").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GREEN)));
            container.setItem(2, buy10);

            ItemStack buy64 = new ItemStack(Items.LIME_STAINED_GLASS_PANE);
            buy64.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Buy x64").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GREEN)));
            container.setItem(3, buy64);

            ItemStack info = new ItemStack(Items.PAPER);
            info.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(stock.name));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("Price: " + EconomyCraft.formatMoney(Math.round(stock.price))));
            info.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
            container.setItem(4, info);

            ItemStack sell1 = new ItemStack(Items.ORANGE_STAINED_GLASS_PANE);
            sell1.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Sell x1").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.RED)));
            container.setItem(5, sell1);

            ItemStack sell10 = new ItemStack(Items.ORANGE_STAINED_GLASS_PANE);
            sell10.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Sell x10").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.RED)));
            container.setItem(6, sell10);

            ItemStack sellAll = new ItemStack(Items.RED_STAINED_GLASS_PANE);
            sellAll.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Sell ALL").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_RED)));
            container.setItem(7, sellAll);

            for (int i = 0; i < 9; i++) {
                this.addSlot(new Slot(container, i, 8 + i * 18, 20) {
                    @Override public boolean mayPickup(Player player) { return false; }
                });
            }

            int y = 40;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) {
                    this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, y + r * 18));
                }
            }
            for (int c = 0; c < 9; c++) {
                this.addSlot(new Slot(inv, c, 8 + c * 18, y + 58));
            }
        }

        @Override
        public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP) {
                ServerPlayer sp = (ServerPlayer) player;
                if (slot == 1) { attemptBuy(sp, 1); return; }
                if (slot == 2) { attemptBuy(sp, 10); return; }
                if (slot == 3) { attemptBuy(sp, 64); return; }
                if (slot == 5) { attemptSell(sp, 1); return; }
                if (slot == 6) { attemptSell(sp, 10); return; }
                if (slot == 7) { attemptSellAll(sp); return; }
            }
            super.clicked(slot, dragType, type, player);
        }

        private void attemptBuy(ServerPlayer sp, long qty) {
            long total = Math.round(stock.price * qty);
            long confirmThreshold = com.reazip.economycraft.EconomyConfig.get().stockConfirmThresholdValue;
            if (total >= confirmThreshold) {
                StocksUi.openConfirmTrade(sp, stock, qty, true);
                return;
            }
            StockManager.TradeResult res = StockManager.get(sp.level().getServer()).buy(sp.getUUID(), stock.id, qty);
            switch (res) {
                case SUCCESS -> sp.sendSystemMessage(Component.literal("Bought " + qty + " shares of " + stock.name + " for " + EconomyCraft.formatMoney(total)).withStyle(ChatFormatting.GREEN));
                case INSUFFICIENT_FUNDS -> sp.sendSystemMessage(Component.literal("Buy failed: insufficient funds").withStyle(ChatFormatting.RED));
                case COOLDOWN -> sp.sendSystemMessage(Component.literal("Buy failed: trade cooldown active").withStyle(ChatFormatting.RED));
                case OVERSIZED -> sp.sendSystemMessage(Component.literal("Buy failed: trade exceeds max allowed quantity").withStyle(ChatFormatting.RED));
                default -> sp.sendSystemMessage(Component.literal("Buy failed").withStyle(ChatFormatting.RED));
            }
            sp.closeContainer();
            StocksUi.open(sp);
        }

        private void attemptSell(ServerPlayer sp, long qty) {
            long total = Math.round(stock.price * qty);
            long confirmThreshold = com.reazip.economycraft.EconomyConfig.get().stockConfirmThresholdValue;
            if (total >= confirmThreshold) {
                StocksUi.openConfirmTrade(sp, stock, qty, false);
                return;
            }
            StockManager.TradeResult res = StockManager.get(sp.level().getServer()).sell(sp.getUUID(), stock.id, qty);
            switch (res) {
                case SUCCESS -> sp.sendSystemMessage(Component.literal("Sold " + qty + " shares of " + stock.name + " for " + EconomyCraft.formatMoney(total)).withStyle(ChatFormatting.GREEN));
                case INSUFFICIENT_HOLDINGS -> sp.sendSystemMessage(Component.literal("Sell failed: insufficient holdings").withStyle(ChatFormatting.RED));
                case COOLDOWN -> sp.sendSystemMessage(Component.literal("Sell failed: trade cooldown active").withStyle(ChatFormatting.RED));
                default -> sp.sendSystemMessage(Component.literal("Sell failed").withStyle(ChatFormatting.RED));
            }
            sp.closeContainer();
            StocksUi.open(sp);
        }

        private void attemptSellAll(ServerPlayer sp) {
            var map = StockManager.get(sp.level().getServer()).getHoldings(sp.getUUID());
            var h = map.get(stock.id);
            long qty = h == null ? 0L : h.quantity;
            if (qty <= 0) {
                sp.sendSystemMessage(Component.literal("You do not own any shares of " + stock.name).withStyle(ChatFormatting.RED));
                return;
            }
            attemptSell(sp, qty);
        }

        @Override public boolean stillValid(Player p) { return true; }
        @Override public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) { return net.minecraft.world.item.ItemStack.EMPTY; }
    }

    static void openConfirmTrade(ServerPlayer player, StockEntry stock, long qty, boolean isBuy) {
        player.openMenu(new net.minecraft.world.MenuProvider() {
            @Override public Component getDisplayName() { return Component.literal(isBuy ? "Confirm Buy" : "Confirm Sell"); }
            @Override public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inv, net.minecraft.world.entity.player.Player p) {
                return new ConfirmTradeMenu(id, inv, stock, qty, isBuy, player);
            }
        });
    }

    private static class ConfirmTradeMenu extends AbstractContainerMenu {
        private final StockEntry stock;
        private final long qty;
        private final boolean isBuy;
        private final ServerPlayer viewer;
        private final SimpleContainer container = new SimpleContainer(9);

        ConfirmTradeMenu(int id, Inventory inv, StockEntry stock, long qty, boolean isBuy, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x1, id);
            this.stock = stock; this.qty = qty; this.isBuy = isBuy; this.viewer = viewer;

            ItemStack confirm = new ItemStack(Items.LIME_STAINED_GLASS_PANE);
            confirm.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Confirm").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GREEN)));
            container.setItem(2, confirm);

            ItemStack info = new ItemStack(Items.PAPER);
            info.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(stock.name));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal((isBuy?"Buy":"Sell") + " " + qty + " @ " + EconomyCraft.formatMoney(Math.round(stock.price))));
            info.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
            container.setItem(4, info);

            ItemStack cancel = new ItemStack(Items.RED_STAINED_GLASS_PANE);
            cancel.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Cancel").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_RED)));
            container.setItem(6, cancel);

            for (int i = 0; i < 9; i++) this.addSlot(new Slot(container, i, 8 + i * 18, 20) { @Override public boolean mayPickup(Player p) { return false; } });
            int y = 40;
            for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, y + r * 18));
            for (int c = 0; c < 9; c++) this.addSlot(new Slot(inv, c, 8 + c * 18, y + 58));
        }

        @Override
        public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP) {
                if (slot == 2) {
                    ServerPlayer sp = (ServerPlayer) player;
                    StockManager.TradeResult res = isBuy
                            ? StockManager.get(sp.level().getServer()).buy(sp.getUUID(), stock.id, qty)
                            : StockManager.get(sp.level().getServer()).sell(sp.getUUID(), stock.id, qty);
                    if (res == StockManager.TradeResult.SUCCESS) {
                        sp.sendSystemMessage(Component.literal((isBuy?"Bought ":"Sold ") + qty + " " + stock.name).withStyle(ChatFormatting.GREEN));
                    } else {
                        sp.sendSystemMessage(Component.literal("Trade failed: " + res.name()).withStyle(ChatFormatting.RED));
                    }
                    sp.closeContainer(); StocksUi.open(sp);
                    return;
                }
                if (slot == 6) { player.closeContainer(); StocksUi.open((ServerPlayer) player); return; }
            }
            super.clicked(slot, dragType, type, player);
        }

        @Override public boolean stillValid(Player p) { return true; }
        @Override public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) { return net.minecraft.world.item.ItemStack.EMPTY; }
    }
}
