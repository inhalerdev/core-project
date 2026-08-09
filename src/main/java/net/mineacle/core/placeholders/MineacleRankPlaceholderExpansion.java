package net.mineacle.core.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.mineacle.core.common.player.RankDisplayResolver;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class MineacleRankPlaceholderExpansion
        extends PlaceholderExpansion {

    private final Core.Core core;

    public MineacleRankPlaceholderExpansion(Core.Core core) {
        this.core = core;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "mineaclerank";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mineacle";
    }

    @Override
    public @NotNull String getVersion() {
        return core.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onRequest(
            OfflinePlayer player,
            @NotNull String params
    ) {
        if (player == null) {
            return "";
        }

        RankDisplayResolver.DisplayRank rank =
                RankDisplayResolver.resolve(player);
        String key = params
                .trim()
                .toLowerCase(Locale.ROOT);

        return switch (key) {
            case "prefix" -> rank.prefix();
            case "key" -> rank.key();
            case "name" -> rank.name();
            case "weight" ->
                    String.valueOf(rank.weight());
            default -> null;
        };
    }
}
