package net.mineacle.core.admininspect.command;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.service.AdminInspectService;
import net.mineacle.core.admininspect.service.OfflineInspectService;

public final class EnderChestCommand
        extends AbstractInspectCommand {

    public EnderChestCommand(
            Core core,
            AdminInspectService service,
            OfflineInspectService offlineService
    ) {
        super(
                core,
                service,
                offlineService,
                AdminInspectService.InspectType.ENDER_CHEST
        );
    }
}
