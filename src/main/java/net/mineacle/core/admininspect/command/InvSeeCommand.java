package net.mineacle.core.admininspect.command;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.service.AdminInspectService;
import net.mineacle.core.admininspect.service.OfflineInspectService;

public final class InvSeeCommand
        extends AbstractInspectCommand {

    public InvSeeCommand(
            Core core,
            AdminInspectService service,
            OfflineInspectService offlineService
    ) {
        super(
                core,
                service,
                offlineService,
                AdminInspectService.InspectType.INVENTORY
        );
    }
}
