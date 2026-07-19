package net.mineacle.core.bounty;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BountyRepository {

    void initialize() throws IOException;

    Optional<BountyRecord> find(UUID targetId);

    List<BountyRecord> listAll();

    void save(BountyRecord record) throws IOException;

    boolean delete(UUID targetId) throws IOException;

    void flush() throws IOException;
}
