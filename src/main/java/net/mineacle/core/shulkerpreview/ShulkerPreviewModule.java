package net.mineacle.core.shulkerpreview;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;

public final class ShulkerPreviewModule extends Module {

    @Override
    public String name() {
        return "ShulkerPreview";
    }

    @Override
    public void enable(Core core) {
        core.getServer().getPluginManager().registerEvents(new ShulkerPreviewListener(core), core);
    }

    @Override
    public void disable() {
    }
}
