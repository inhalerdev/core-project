package net.mineacle.core.bootstrap;

import net.mineacle.core.Core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public final class ModuleManager {

    private final Core core;
    private final List<Module> modules = new ArrayList<>();
    private final Map<String, Module> modulesByName = new LinkedHashMap<>();
    private boolean shuttingDown;

    public ModuleManager(Core core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    /**
     * Enables and records one module.
     *
     * A failed module is given one cleanup attempt before the exception is
     * propagated to Core. Previously enabled modules remain available for the
     * caller to roll back through {@link #disableAll()}.
     */
    public synchronized void register(Module module) throws Exception {
        Objects.requireNonNull(module, "module");

        if (shuttingDown) {
            throw new IllegalStateException(
                    "Cannot register modules while MineacleCore is shutting down"
            );
        }

        String displayName = validatedName(module);
        String key = displayName.toLowerCase(Locale.ROOT);

        if (modulesByName.containsKey(key)) {
            throw new IllegalStateException(
                    "Duplicate MineacleCore module name: " + displayName
            );
        }

        for (Module registered : modules) {
            if (registered.getClass().equals(module.getClass())) {
                throw new IllegalStateException(
                        "Duplicate MineacleCore module class: "
                                + module.getClass().getName()
                );
            }
        }

        long startedAt = System.nanoTime();

        try {
            module.enable(core);
        } catch (Exception enableFailure) {
            try {
                module.disable();
            } catch (Exception cleanupFailure) {
                enableFailure.addSuppressed(cleanupFailure);
            }

            throw enableFailure;
        }

        modules.add(module);
        modulesByName.put(key, module);

        core.getLogger().info(
                "Initialized module: "
                        + displayName
                        + " ("
                        + elapsedMillis(startedAt)
                        + "ms)"
        );
    }

    /**
     * Disables registered modules in reverse dependency order.
     *
     * The method is idempotent, so it is safe for both startup rollback and
     * Bukkit's normal onDisable lifecycle.
     */
    public synchronized void disableAll() {
        if (shuttingDown) {
            return;
        }

        shuttingDown = true;

        for (int index = modules.size() - 1; index >= 0; index--) {
            Module module = modules.get(index);
            String displayName = safeName(module);
            long startedAt = System.nanoTime();

            try {
                module.disable();
                core.getLogger().info(
                        "Disabled module: "
                                + displayName
                                + " ("
                                + elapsedMillis(startedAt)
                                + "ms)"
                );
            } catch (Exception exception) {
                core.getLogger().log(
                        Level.WARNING,
                        "Failed to disable module " + displayName,
                        exception
                );
            }
        }

        modules.clear();
        modulesByName.clear();
    }

    public synchronized List<Module> modules() {
        return Collections.unmodifiableList(new ArrayList<>(modules));
    }

    public synchronized Module module(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        return modulesByName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized int size() {
        return modules.size();
    }

    private String validatedName(Module module) {
        String name = module.name();

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Module " + module.getClass().getName()
                            + " returned an empty name"
            );
        }

        return name.trim();
    }

    private String safeName(Module module) {
        try {
            return validatedName(module);
        } catch (RuntimeException ignored) {
            return module.getClass().getSimpleName();
        }
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(
                0L,
                (System.nanoTime() - startedAt) / 1_000_000L
        );
    }
}
