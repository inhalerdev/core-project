package net.mineacle.core.economy.service;

import net.mineacle.core.common.text.TextColor;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public final class OfflinePaymentNotice {

    private long totalCents;
    private final Set<String> senders = new LinkedHashSet<>();

    public OfflinePaymentNotice(
            long totalCents,
            Collection<String> senders
    ) {
        this.totalCents = Math.max(0L, totalCents);

        if (senders != null) {
            for (String sender : senders) {
                addSender(sender);
            }
        }
    }

    public OfflinePaymentNotice copy() {
        return new OfflinePaymentNotice(totalCents, senders);
    }

    public long totalCents() {
        return totalCents;
    }

    public Set<String> senders() {
        return Set.copyOf(senders);
    }

    public boolean tryAdd(
            long cents,
            String senderName
    ) {
        if (cents <= 0L) {
            return false;
        }

        try {
            totalCents = Math.addExact(totalCents, cents);
        } catch (ArithmeticException exception) {
            return false;
        }

        addSender(senderName);
        return true;
    }

    /**
     * Compatibility method retained for older MineacleCore callers.
     */
    public void add(long cents, String senderName) {
        tryAdd(cents, senderName);
    }

    public boolean singleSender() {
        return senders.size() == 1;
    }

    public String singleSenderName() {
        if (!singleSender()) {
            return "";
        }

        return senders.iterator().next();
    }

    private void addSender(String senderName) {
        if (senderName == null || senderName.isBlank()) {
            return;
        }

        String clean = TextColor.strip(senderName).trim();

        if (!clean.isBlank()) {
            senders.add(clean);
        }
    }
}
