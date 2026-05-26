package net.mineacle.core.punish.model;

import java.util.UUID;

public final class PunishSession {
    private final UUID targetUuid;
    private final String targetName;
    private PunishAction action;
    private PunishReason reason;
    private PunishDuration duration;
    private boolean confirmReady;

    public PunishSession(UUID targetUuid, String targetName) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
    }

    public PunishAction action() {
        return action;
    }

    public void action(PunishAction action) {
        this.action = action;
        this.confirmReady = false;
    }

    public PunishReason reason() {
        return reason;
    }

    public void reason(PunishReason reason) {
        this.reason = reason;
        this.confirmReady = false;
    }

    public PunishDuration duration() {
        return duration;
    }

    public void duration(PunishDuration duration) {
        this.duration = duration;
        this.confirmReady = false;
    }

    public boolean confirmReady() {
        return confirmReady;
    }

    public void confirmReady(boolean confirmReady) {
        this.confirmReady = confirmReady;
    }
}
