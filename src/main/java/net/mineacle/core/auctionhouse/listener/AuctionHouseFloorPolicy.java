package net.mineacle.core.auctionhouse.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Makes the Auction House server-Sell floor a fail-closed economic invariant.
 *
 * <p>The old {@code listing.enforce-server-sell-floor} setting is retained
 * only as a migration check for already deployed configs. New configs no
 * longer expose it. If an old config explicitly sets it to false, Auction
 * House initialization/reload is blocked rather than allowing a listing below
 * the current exact /sell value.</p>
 */
public final class AuctionHouseFloorPolicy
        implements Listener {

    private static final String CONFIG_NAME =
            "auctionhouse.yml";
    private static final String LEGACY_BYPASS_PATH =
            "listing.enforce-server-sell-floor";

    private final Core core;

    public AuctionHouseFloorPolicy(
            Core core
    ) {
        this.core =
                Objects.requireNonNull(
                        core,
                        "core"
                );
    }

    public static void requireEnabled(
            Core core
    ) {
        PolicyCheck check =
                check(
                        Objects.requireNonNull(
                                core,
                                "core"
                        )
                );

        if (!check.allowed()) {
            throw new IllegalStateException(
                    check.problem()
            );
        }
    }

    @EventHandler(
            priority = EventPriority.LOWEST,
            ignoreCancelled = true
    )
    public void onCommand(
            PlayerCommandPreprocessEvent event
    ) {
        if (!isAuctionReload(
                event.getMessage()
        )) {
            return;
        }

        PolicyCheck check =
                check(core);

        if (check.allowed()) {
            return;
        }

        event.setCancelled(true);

        String message =
                "&cAuction House reload blocked "
                        + "&#bbbbbb— the server Sell floor cannot be disabled";

        event.getPlayer().sendMessage(
                TextColor.color(
                        message
                )
        );
        event.getPlayer().sendActionBar(
                GuiText.component(
                        message
                )
        );
        SoundService.guiError(
                event.getPlayer(),
                core
        );

        core.getLogger().severe(
                "[AuctionHouse] Reload blocked: "
                        + check.problem()
        );
    }

    public void shutdown() {
        HandlerList.unregisterAll(
                this
        );
    }

    private static PolicyCheck check(
            Core core
    ) {
        File configFile =
                new File(
                        core.getDataFolder(),
                        CONFIG_NAME
                );

        if (!configFile.isFile()) {
            if (configFile.exists()) {
                return PolicyCheck.blocked(
                        CONFIG_NAME
                                + " exists but is not a regular file"
                );
            }

            try {
                core.saveResource(
                        CONFIG_NAME,
                        false
                );
            } catch (RuntimeException exception) {
                core.getLogger().log(
                        Level.SEVERE,
                        "[AuctionHouse] Could not create "
                                + CONFIG_NAME,
                        exception
                );
                return PolicyCheck.blocked(
                        "could not create "
                                + CONFIG_NAME
                );
            }
        }

        YamlConfiguration configuration =
                new YamlConfiguration();

        try {
            configuration.load(
                    configFile
            );
        } catch (
                IOException
                | InvalidConfigurationException
                | RuntimeException exception
        ) {
            core.getLogger().log(
                    Level.SEVERE,
                    "[AuctionHouse] Could not validate mandatory server Sell floor policy",
                    exception
            );
            return PolicyCheck.blocked(
                    CONFIG_NAME
                            + " could not be parsed safely"
            );
        }

        if (!configuration.getBoolean(
                LEGACY_BYPASS_PATH,
                true
        )) {
            return PolicyCheck.blocked(
                    LEGACY_BYPASS_PATH
                            + "=false is no longer supported"
            );
        }

        return PolicyCheck.allowedCheck();
    }

    private static boolean isAuctionReload(
            String rawMessage
    ) {
        if (rawMessage == null
                || rawMessage.length() <= 1) {
            return false;
        }

        String normalized =
                rawMessage.substring(1)
                        .trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (normalized.isBlank()) {
            return false;
        }

        String[] parts =
                normalized.split(
                        "\\s+"
                );

        if (parts.length < 2
                || !parts[1].equals(
                "reload"
        )) {
            return false;
        }

        return parts[0].equals(
                "ah"
        )
                || parts[0].equals(
                "auction"
        )
                || parts[0].equals(
                "auctionhouse"
        );
    }

    private record PolicyCheck(
            boolean allowed,
            String problem
    ) {
        private PolicyCheck {
            problem = problem == null
                    ? ""
                    : problem;
        }

        private static PolicyCheck allowedCheck() {
            return new PolicyCheck(
                    true,
                    ""
            );
        }

        private static PolicyCheck blocked(
                String problem
        ) {
            return new PolicyCheck(
                    false,
                    problem
            );
        }
    }
}
