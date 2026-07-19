package net.mineacle.core.bootstrap;

import net.mineacle.core.Core;

public abstract class Module {

    /**
     * Returns the stable human-readable module name used in lifecycle logs.
     */
    public abstract String name();

    /**
     * Enables the module and registers its runtime resources.
     */
    public abstract void enable(Core core) throws Exception;

    /**
     * Releases tasks, caches, storage handles, and other module-owned state.
     */
    public abstract void disable() throws Exception;
}
