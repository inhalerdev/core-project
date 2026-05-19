package net.mineacle.core.bounty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BountyRepository {

    void initialize() throws Exception;

    Optional<BountyRecord> find(UUID targetId) throws Exception;

    List<BountyRecord> listAll() throws Exception;

    void save(BountyRecord record) throws Exception;

    boolean delete(UUID targetId) throws Exception;
}