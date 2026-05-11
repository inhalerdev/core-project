package net.mineacle.core.bootstrap;

import net.mineacle.core.Core;

public abstract class Module {

    public abstract String name();

    public abstract void enable(Core core) throws Exception;

    public abstract void disable() throws Exception;
}