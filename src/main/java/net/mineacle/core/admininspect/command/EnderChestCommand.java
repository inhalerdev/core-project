package net.mineacle.core.admininspect.command;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.service.AdminInspectService;

public final class EnderChestCommand
        extends AbstractInspectCommand {

    public EnderChestCommand(
            Core core,
            AdminInspectService service
    ) {
        super(
                core,
                service,
                AdminInspectService
                        .InspectType
                        .ENDER_CHEST
        );
    }
}
