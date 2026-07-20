package net.mineacle.core.links.listener;

import net.mineacle.core.links.command.LinksCommand;
import org.bukkit.event.Listener;

/**
 * Retained only as a source-compatible shell for older local worktrees.
 *
 * Unregistered aliases must not be intercepted through command preprocess
 * events. Bukkit, plugin.yml, permissions, and Mineacle Security own command
 * registration and root visibility.
 */
@Deprecated
public final class LinksCommandListener implements Listener {

    public LinksCommandListener(LinksCommand ignored) {
    }
}
