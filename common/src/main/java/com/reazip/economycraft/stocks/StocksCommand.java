package com.reazip.economycraft.stocks;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.reazip.economycraft.util.PermissionCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class StocksCommand {
    private StocksCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        LiteralArgumentBuilder<CommandSourceStack> base = literal("stocks")
                .executes(ctx -> {
                    try {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        StocksUi.open(p);
                        return 1;
                    } catch (Exception e) {
                        ctx.getSource().sendFailure(Component.literal("Only players can use this command."));
                        return 0;
                    }
                });

        // admin subcommands
        LiteralArgumentBuilder<CommandSourceStack> admin = literal("admin").requires(PermissionCompat.gamemaster());

        admin.then(literal("reload").executes(ctx -> {
            var server = ctx.getSource().getServer();
            StockManager.get(server).reload();
            ctx.getSource().sendSuccess(() -> Component.literal("Stocks reloaded."), true);
            return 1;
        }));

        admin.then(literal("save").executes(ctx -> {
            var server = ctx.getSource().getServer();
            StockManager.get(server).save();
            ctx.getSource().sendSuccess(() -> Component.literal("Stocks saved."), true);
            return 1;
        }));

        admin.then(
            literal("setprice").then(
                argument("stock", StringArgumentType.word()).then(
                    argument("price", DoubleArgumentType.doubleArg()).executes(ctx -> {
                        var server = ctx.getSource().getServer();
                        String stockId = StringArgumentType.getString(ctx, "stock");
                        double price = DoubleArgumentType.getDouble(ctx, "price");
                        StockManager.get(server).setPrice(stockId, price);
                        ctx.getSource().sendSuccess(() -> Component.literal("Set price of " + stockId + " to " + price), true);
                        return 1;
                    })
                )
            )
        );

        admin.then(
            literal("give").then(
                argument("player", EntityArgument.player()).then(
                    argument("stock", StringArgumentType.word()).then(
                        argument("qty", LongArgumentType.longArg(1)).executes(ctx -> {
                            var server = ctx.getSource().getServer();
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                            String stockId = StringArgumentType.getString(ctx, "stock");
                            long qty = LongArgumentType.getLong(ctx, "qty");
                            StockEntry se = StockManager.get(server).get(stockId);
                            if (se == null) {
                            ctx.getSource().sendFailure(Component.literal("Unknown stock: " + stockId));
                            return 0;
                            }
                            StockManager.get(server).addHolding(target.getUUID(), stockId, qty, se.price);
                            ctx.getSource().sendSuccess(() -> Component.literal("Gave " + qty + " " + stockId + " to " + target.getName().getString()), true);
                            return 1;
                        })
                    )
                )
            )
        );

        admin.then(literal("holdings").then(argument("player", EntityArgument.player())
                .executes(ctx -> {
                    var server = ctx.getSource().getServer();
                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                    var map = StockManager.get(server).getHoldings(target.getUUID());
                    if (map.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> Component.literal(target.getName().getString() + " has no holdings."), false);
                        return 1;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("Holdings for ").append(target.getName().getString()).append(": ");
                    boolean first = true;
                    for (var e : map.entrySet()) {
                        if (!first) sb.append(", ");
                        first = false;
                        sb.append(e.getKey()).append(" x").append(e.getValue().quantity);
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                    return 1;
                })));

        base.then(admin);

        return base;
    }
}
