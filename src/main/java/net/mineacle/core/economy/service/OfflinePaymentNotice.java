package net.mineacle.core.economy.service;

import java.util.HashSet;
import java.util.Set;

public final class OfflinePaymentNotice {

    private long totalCents;
    private final Set<String> senders;

    public OfflinePaymentNotice(long totalCents, Set<String> senders) {
        this.totalCents = totalCents;
        this.senders = new HashSet<>(senders);
    }

    public long totalCents() {
        return totalCents;
    }

    public Set<String> senders() {
        return senders;
    }

    public void add(long cents, String senderName) {
        totalCents += cents;

        if (senderName != null && !senderName.isBlank()) {
            senders.add(senderName);
        }
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
}