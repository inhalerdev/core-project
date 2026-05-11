package net.mineacle.core.rtp.service;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Random;
import java.util.concurrent.CompletableFuture;

public final class OriginRtpLocationService {

    private final Core core;
    private final Random random = new Random();

    public OriginRtpLocationService(Core core) {
        this.core = core;
    }

    public CompletableFuture<Location> findSafeLocation() {
        CompletableFuture<Location> future = new CompletableFuture<>();

        OriginRtpSearchSettings settings = OriginRtpSearchSettings.fromConfig(core);
        World world = Bukkit.getWorld(settings.worldName());

        if (world == null) {
            future.complete(null);
            return future;
        }

        tryFind(world, settings, 0, future);
        return future;
    }

    private void tryFind(World world, OriginRtpSearchSettings settings, int attempt, CompletableFuture<Location> future) {
        if (future.isDone()) {
            return;
        }

        if (attempt >= settings.maxAttempts()) {
            future.complete(null);
            return;
        }

        int[] coordinates = randomCoordinates(world, settings);
        int x = coordinates[0];
        int z = coordinates[1];

        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        world.getChunkAtAsync(chunkX, chunkZ, true).thenAccept(chunk -> {
            Bukkit.getScheduler().runTask(core, () -> {
                preloadNearby(world, chunk, settings.preloadRadius());

                Location location = safeLocationAt(world, x, z, settings);

                if (location != null) {
                    future.complete(location);
                    return;
                }

                tryFind(world, settings, attempt + 1, future);
            });
        }).exceptionally(throwable -> {
            Bukkit.getScheduler().runTask(core, () -> tryFind(world, settings, attempt + 1, future));
            return null;
        });
    }

    private int[] randomCoordinates(World world, OriginRtpSearchSettings settings) {
        if (settings.useWorldBorder()) {
            WorldBorder border = world.getWorldBorder();
            Location borderCenter = border.getCenter();
            double halfSize = border.getSize() / 2.0D;

            int minX = (int) Math.ceil(borderCenter.getX() - halfSize);
            int maxX = (int) Math.floor(borderCenter.getX() + halfSize);
            int minZ = (int) Math.ceil(borderCenter.getZ() - halfSize);
            int maxZ = (int) Math.floor(borderCenter.getZ() + halfSize);

            for (int attempt = 0; attempt < 64; attempt++) {
                int x = randomBetween(minX, maxX);
                int z = randomBetween(minZ, maxZ);

                if (distanceFromCenter(x, z, settings.centerX(), settings.centerZ()) >= settings.minRadius()) {
                    return new int[]{x, z};
                }
            }

            return new int[]{randomBetween(minX, maxX), randomBetween(minZ, maxZ)};
        }

        if (settings.shape().equalsIgnoreCase("circle")) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius = settings.minRadius() + (random.nextDouble() * (settings.maxRadius() - settings.minRadius()));

            int x = settings.centerX() + (int) Math.round(Math.cos(angle) * radius);
            int z = settings.centerZ() + (int) Math.round(Math.sin(angle) * radius);

            return new int[]{x, z};
        }

        int x;
        int z;

        do {
            x = settings.centerX() + randomBetween(-settings.maxRadius(), settings.maxRadius());
            z = settings.centerZ() + randomBetween(-settings.maxRadius(), settings.maxRadius());
        } while (distanceFromCenter(x, z, settings.centerX(), settings.centerZ()) < settings.minRadius());

        return new int[]{x, z};
    }

    private Location safeLocationAt(World world, int x, int z, OriginRtpSearchSettings settings) {
        Block top = world.getHighestBlockAt(x, z);
        Block ground = findGroundBelow(top, settings);

        if (ground == null) {
            return null;
        }

        int groundY = ground.getY();
        int feetY = groundY + 1;
        int headY = groundY + 2;

        if (feetY < settings.minY()) {
            return null;
        }

        if (headY > settings.maxY()) {
            return null;
        }

        Block feet = world.getBlockAt(x, feetY, z);
        Block head = world.getBlockAt(x, headY, z);

        if (!isSafeGround(ground, settings)) {
            return null;
        }

        if (!isSafeAir(feet, settings)) {
            return null;
        }

        if (!isSafeAir(head, settings)) {
            return null;
        }

        if (isUnsafeNearby(ground, settings)) {
            return null;
        }

        return new Location(world, x + 0.5D, feetY, z + 0.5D, randomYaw(), 0.0F);
    }

    private Block findGroundBelow(Block start, OriginRtpSearchSettings settings) {
        if (start == null || start.getWorld() == null) {
            return null;
        }

        World world = start.getWorld();
        int x = start.getX();
        int z = start.getZ();

        int startY = Math.min(start.getY(), settings.maxY());

        for (int y = startY; y >= settings.minY() - 1; y--) {
            Block block = world.getBlockAt(x, y, z);

            if (isSafeGround(block, settings)) {
                return block;
            }
        }

        return null;
    }

    private boolean isSafeGround(Block block, OriginRtpSearchSettings settings) {
        if (block == null) {
            return false;
        }

        Material material = block.getType();

        if (settings.unsafeBlocks().contains(material)) {
            return false;
        }

        if (!material.isSolid()) {
            return false;
        }

        String name = material.name();

        if (name.endsWith("_LEAVES")) {
            return false;
        }

        if (name.endsWith("_LOG")) {
            return false;
        }

        if (name.endsWith("_WOOD")) {
            return false;
        }

        return true;
    }

    private boolean isSafeAir(Block block, OriginRtpSearchSettings settings) {
        if (block == null) {
            return false;
        }

        Material material = block.getType();

        if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            return true;
        }

        if (settings.unsafeBlocks().contains(material)) {
            return false;
        }

        return block.isPassable();
    }

    private boolean isUnsafeNearby(Block ground, OriginRtpSearchSettings settings) {
        Block[] nearby = {
                ground,
                ground.getRelative(BlockFace.NORTH),
                ground.getRelative(BlockFace.SOUTH),
                ground.getRelative(BlockFace.EAST),
                ground.getRelative(BlockFace.WEST),
                ground.getRelative(BlockFace.UP),
                ground.getRelative(BlockFace.DOWN)
        };

        for (Block block : nearby) {
            Material material = block.getType();

            if (material == Material.WATER
                    || material == Material.LAVA
                    || material == Material.FIRE
                    || material == Material.SOUL_FIRE
                    || material == Material.CACTUS
                    || material == Material.MAGMA_BLOCK
                    || material == Material.POWDER_SNOW) {
                return true;
            }

            if (settings.unsafeBlocks().contains(material)
                    && material != Material.AIR
                    && material != Material.CAVE_AIR
                    && material != Material.VOID_AIR) {
                return true;
            }
        }

        return false;
    }

    private void preloadNearby(World world, Chunk center, int radius) {
        if (radius <= 0) {
            return;
        }

        int centerX = center.getX();
        int centerZ = center.getZ();

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                world.getChunkAtAsync(x, z, true);
            }
        }
    }

    private int randomBetween(int min, int max) {
        if (max <= min) {
            return min;
        }

        return min + random.nextInt((max - min) + 1);
    }

    private double distanceFromCenter(int x, int z, int centerX, int centerZ) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return Math.sqrt((dx * dx) + (dz * dz));
    }

    private float randomYaw() {
        return random.nextFloat() * 360.0F;
    }
}