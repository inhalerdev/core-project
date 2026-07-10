package net.mineacle.core.webprofiles.model;

public record WebTeamRecord(
        String teamId,
        String teamName,
        String founderUuid,
        int memberCount,
        int onlineMembers,
        long totalBalanceCents,
        String totalBalanceFormatted,
        long totalKills,
        long totalDeaths,
        double kdRatio,
        int capitalRank,
        int kdRank,
        long updatedAt
) {
}