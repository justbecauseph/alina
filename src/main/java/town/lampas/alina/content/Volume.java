package town.lampas.alina.content;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A box of whole blocks in one dimension, both corners included. {@code min} and {@code max} are ordered whatever
 * order the content wrote the corners in.
 */
public record Volume(Identifier id, ResourceKey<Level> dimension, BlockPos min, BlockPos max) {

    /** True when {@code position} in {@code level} is inside the blocks from {@code min} to {@code max} inclusive. */
    public boolean contains(ResourceKey<Level> level, Vec3 position) {
        return level.equals(dimension)
                && position.x >= min.getX() && position.x < max.getX() + 1
                && position.y >= min.getY() && position.y < max.getY() + 1
                && position.z >= min.getZ() && position.z < max.getZ() + 1;
    }
}
