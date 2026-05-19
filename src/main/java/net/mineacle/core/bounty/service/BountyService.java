package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

public final class BountyService {

    private final Core core;
    private final BountyRepository repository;

    public BountyService(Core core, BountyRepository repository) {
        this.core = core;
        this.repository = repository;
    }

    public List<BountyRecord> listBounties() throws Exception {
        return repository.listAll();
    }

    public boolean placeBounty(Player setter, Player target, long amount) throws Exception {
        if (setter.getUniqueId().equals(target.getUniqueId())) {
            setter.sendMessage(resolve("bounty.self", "&cYou cannot place a bounty on yourself."));
            return true;
        }

        long min = core.getConfig().getLong("bounty.minimum", 100L);
        long max = core.getConfig().getLong("bounty.maximum", 1_000_000L);
        EconomyService economy = economy();

        if (amount < min) {
            String minText = economy != null ? economy.formatCompact(min) : String.valueOf(min);
            setter.sendMessage(resolve("bounty.too-low", "&cMinimum bounty is &f%amount%")
                    .replace("%amount%", minText));
            return true;
        }

        if (amount > max) {
            String maxText = economy != null ? economy.formatCompact(max) : String.valueOf(max);
            setter.sendMessage(resolve("bounty.too-high", "&cMaximum bounty is &f%amount%")
                    .replace("%amount%", maxText));
            return true;
        }

        if (economy == null) {
            setter.sendMessage(resolve("bounty.no-economy", "&cNative economy is unavailable."));
            return true;
        }

        if (!economy.has(setter, amount)) {
            setter.sendMessage(resolve("bounty.not-enough-money", "&cYou do not have enough money."));
            return true;
        }

        if (!economy.withdraw(setter, amount)) {
            setter.sendMessage(resolve("bounty.withdraw-failed", "&cFailed to withdraw money for this bounty."));
            return true;
        }

        Optional<BountyRecord> existing = repository.find(target.getUniqueId());
        long newAmount = existing.map(record -> safeAdd(record.amount(), amount)).orElse(amount);

        repository.save(new BountyRecord(
                target.getUniqueId(),
                safeName(target),
                newAmount,
                System.currentTimeMillis()
        ));

        setter.sendMessage(resolve("bounty.placed", "&7Placed &6%amount% &7on &6%player%")
                .replace("%amount%", economy.formatCompact(amount))
                .replace("%player%", safeName(target)));

        target.sendMessage(resolve("bounty.target-notified", "&cA bounty has been placed on you. Current total: &f%amount%")
                .replace("%amount%", economy.formatCompact(newAmount)));

        return true;
    }

    public boolean claim(Player killer, Player target) throws Exception {
        Optional<BountyRecord> existing = repository.find(target.getUniqueId());
        if (existing.isEmpty()) {
            return false;
        }

        BountyRecord bounty = existing.get();
        EconomyService economy = economy();

        if (economy != null) {
            economy.deposit(killer, bounty.amount());
        }

        repository.delete(target.getUniqueId());

        String amountText = economy != null ? economy.formatCompact(bounty.amount()) : String.valueOf(bounty.amount());

        killer.sendMessage(resolve("bounty.claimed", "&7You claimed &6%amount% &7for killing &6%player%")
                .replace("%amount%", amountText)
                .replace("%player%", safeName(target)));

        target.sendMessage(resolve("bounty.lost", "&cYour bounty of &f%amount% &cwas claimed.")
                .replace("%amount%", amountText));

        return true;
    }

    private EconomyService economy() {
        for (Module module : core.modules()) {
            if (module instanceof EconomyModule economyModule) {
                return economyModule.economyService();
            }
        }
        return null;
    }

    private long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private String safeName(Player player) {
        return player.getName() != null ? player.getName() : "player";
    }

    private String resolve(String key, String fallback) {
        String raw = core.messages().raw(key);
        return raw == null || raw.equals(key) ? fallback : core.messages().get(key);
    }
}