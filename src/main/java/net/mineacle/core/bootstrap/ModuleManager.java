package net.mineacle.core.bootstrap;

import net.mineacle.core.Core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

public final class ModuleManager {

    private final Core core;
    private final List<Registration> registrations =
            new ArrayList<>();
    private final Map<String, Registration> byName =
            new LinkedHashMap<>();
    private final Set<Class<?>> registeredClasses =
            new HashSet<>();

    private boolean shuttingDown;

    public ModuleManager(Core core) {
        this.core = Objects.requireNonNull(
                core,
                "core"
        );
    }

    /**
     * Enables and records one module.
     * <p>
     * The display name is resolved once at registration and retained for the
     * complete lifecycle. A failed module gets one cleanup attempt before the
     * original exception is propagated to Core for full startup rollback.
     */
    public synchronized void register(
            Module module
    ) throws Exception {
        Objects.requireNonNull(
                module,
                "module"
        );

        if (shuttingDown) {
            throw new IllegalStateException(
                    "Cannot register modules while MineacleCore is shutting down"
            );
        }

        String displayName =
                validatedName(module);
        String key =
                displayName.toLowerCase(
                        Locale.ROOT
                );

        if (byName.containsKey(key)) {
            throw new IllegalStateException(
                    "Duplicate MineacleCore module name: "
                            + displayName
            );
        }

        if (registeredClasses.contains(
                module.getClass()
        )) {
            throw new IllegalStateException(
                    "Duplicate MineacleCore module class: "
                            + module.getClass()
                            .getName()
            );
        }

        long startedAt = System.nanoTime();

        try {
            module.enable(core);
        } catch (Exception enableFailure) {
            try {
                module.disable();
            } catch (Exception cleanupFailure) {
                enableFailure.addSuppressed(
                        cleanupFailure
                );
            }

            throw enableFailure;
        }

        Registration registration =
                new Registration(
                        module,
                        displayName
                );

        registrations.add(registration);
        byName.put(key, registration);
        registeredClasses.add(
                module.getClass()
        );

        core.getLogger().info(
                "Initialized module: "
                        + displayName
                        + " ("
                        + elapsedMillis(
                        startedAt
                )
                        + "ms)"
        );
    }

    /**
     * Disables registered modules in exact reverse initialization order.
     * <p>
     * The method is idempotent so startup rollback and Bukkit's normal plugin
     * shutdown can safely converge on the same lifecycle path.
     */
    public synchronized void disableAll() {
        if (shuttingDown) {
            return;
        }

        shuttingDown = true;

        for (int index =
             registrations.size() - 1;
             index >= 0;
             index--) {
            Registration registration =
                    registrations.get(index);

            long startedAt =
                    System.nanoTime();

            try {
                registration
                        .module()
                        .disable();

                core.getLogger().info(
                        "Disabled module: "
                                + registration
                                .displayName()
                                + " ("
                                + elapsedMillis(
                                startedAt
                        )
                                + "ms)"
                );
            } catch (Exception exception) {
                core.getLogger().log(
                        Level.WARNING,
                        "Failed to disable module "
                                + registration
                                .displayName(),
                        exception
                );
            }
        }

        registrations.clear();
        byName.clear();
        registeredClasses.clear();
    }

    public synchronized List<Module> modules() {
        List<Module> result =
                new ArrayList<>(
                        registrations.size()
                );

        for (Registration registration
                : registrations) {
            result.add(
                    registration.module()
            );
        }

        return List.copyOf(result);
    }

    public synchronized Module module(
            String name
    ) {
        if (name == null
                || name.isBlank()) {
            return null;
        }

        Registration registration =
                byName.get(
                        name.trim()
                                .toLowerCase(
                                        Locale.ROOT
                                )
                );

        return registration == null
                ? null
                : registration.module();
    }

    public synchronized int size() {
        return registrations.size();
    }

    private String validatedName(
            Module module
    ) {
        String name = module.name();

        if (name == null
                || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Module "
                            + module.getClass()
                            .getName()
                            + " returned an empty name"
            );
        }

        return name.trim();
    }

    private long elapsedMillis(
            long startedAt
    ) {
        return Math.max(
                0L,
                (System.nanoTime()
                        - startedAt)
                        / 1_000_000L
        );
    }

    private record Registration(
            Module module,
            String displayName
    ) {
    }
}
