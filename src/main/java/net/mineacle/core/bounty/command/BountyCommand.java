package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class BountyCommand implements CommandExecutor {

    private final Core core;
    private final BountyModule bountyModule;

    public BountyCommand(Core core, BountyModule bountyModule) {
        this.core = core;
        this.bountyModule = bountyModule;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(resolve("general.player-only", "&cPlayers only."));
            return true;
        }

        try {
            if (args.length == 0) {
                if (bountyModule.bountyMainGui() != null) {
                    bountyModule.bountyMainGui().open(player);
                    return true;
                }
                return handleList(player);
            }

            if (args[0].equalsIgnoreCase("list")) {
                return handleList(player);
            }

            if (args.length < 2) {
                player.sendMessage(resolve("bounty.usage", "&cUsage: /bounty <player> <amount>"));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                target = Bukkit.getOnlinePlayers().stream()
                        .filter(other -> other.getName().equalsIgnoreCase(args[0]))
                        .findFirst()
                        .orElse(null);
            }

            if (target == null) {
                player.sendMessage(resolve("bounty.player-not-found", "&cThat player is not online."));
                return true;
            }

            long amount;
            try {
                amount = Long.parseLong(args[1].replace(",", "").replace("$", ""));
            } catch (NumberFormatException exception) {
                player.sendMessage(resolve("bounty.invalid-amount", "&cThat is not a valid amount."));
                return true;
            }

            return bountyModule.bountyService().placeBounty(player, target, amount);
        } catch (Exception exception) {
            core.getLogger().severe("Bounty command failed: " + exception.getMessage());
            player.sendMessage(resolve("general.error", "&cSomething went wrong. Check console."));
            return true;
        }
    }

    private boolean handleList(Player player) throws Exception {
        List<BountyRecord> bounties = bountyModule.bountyService().listBounties();
        if (bounties.isEmpty()) {
            player.sendMessage(resolve("bounty.list-empty", "&7There are no active bounties."));
            return true;
        }

        player.sendMessage(resolve("bounty.list-header", "&6Active Bounties:"));
        for (BountyRecord record : bounties) {
            player.sendMessage(resolve("bounty.list-line", "&6- &f%player% &7- &6%amount%")
                    .replace("%player%", record.targetName())
                    .replace("%amount%", String.valueOf(record.amount())));
        }
        return true;
    }

    private String resolve(String key, String fallback) {
        String raw = core.messages().raw(key);
        return raw == null || raw.equals(key) ? fallback : core.messages().get(key);
    }
}