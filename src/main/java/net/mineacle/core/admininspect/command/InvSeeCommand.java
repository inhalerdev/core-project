package net.mineacle.core.admininspect.command;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.service.AdminInspectService;

public final class InvSeeCommand
        extends AbstractInspectCommand {

    public InvSeeCommand(
            Core core,
            AdminInspectService service
    ) {
        super(
                core,
                service,
                AdminInspectService
                        .InspectType
                        .INVENTORY
        );
    }
}
