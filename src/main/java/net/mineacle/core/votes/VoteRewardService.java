package net.mineacle.core.votes;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class VoteRewardService {

    private final Core core;
    private final File configFile;
    private final File dataFile;

    private FileConfiguration config;
    private FileConfiguration data;

    public VoteRewardService(Core core) {
        this.core = core;
        this.configFile = new File(core.getDataFolder(), "vote-rewards.yml");
        this.dataFile = new File(core.getDataFolder(), "vote-reward-data.yml");
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!configFile.exists()) {
            core.saveResource("vote-rewards.yml", false);
        }

        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException exception) {
                core.getLogger().severe("Could not create vote-reward-data.yml");
                exception.printStackTrace();
            }
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public int requiredVotes() {
        return Math.max(1, config.getInt("required-votes", 3));
    }

    public int addVote(UUID uuid) {
        int votes = data.getInt("players." + uuid + ".votes", 0) + 1;
        data.set("players." + uuid + ".votes", votes);
        save();
        return votes;
    }

    public void resetVotes(UUID uuid) {
        data.set("players." + uuid + ".votes", 0);
        save();
    }

    public void reward(OfflinePlayer player) {
        if (player == null || player.getName() == null) {
            return;
        }

        List<String> commands = config.getStringList("reward.commands");

        for (String command : commands) {
            String parsed = command
                    .replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString());

            core.getServer().dispatchCommand(core.getServer().getConsoleSender(), parsed);
        }
    }

    public void sendProgress(Player player, int votes) {
        if (player == null || !config.getBoolean("messages.progress.enabled", true)) {
            return;
        }

        String message = config.getString("messages.progress.text", "&#bbbbbbVote received")
                .replace("%votes%", String.valueOf(votes))
                .replace("%required%", String.valueOf(requiredVotes()));

        player.sendMessage(TextColor.color(message));
    }

    public void sendReward(Player player) {
        if (player == null || !config.getBoolean("messages.reward.enabled", true)) {
            return;
        }

        player.sendMessage(TextColor.color(config.getString("messages.reward.text", "&#bbbbbbYou earned a &#ff88ffVote Crate Key")));
    }

    public void save() {
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save vote-reward-data.yml");
            exception.printStackTrace();
        }
    }
}
