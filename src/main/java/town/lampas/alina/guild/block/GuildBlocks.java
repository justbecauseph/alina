package town.lampas.alina.guild.block;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import town.lampas.alina.Alina;

/**
 * Registers the Notice Board block and its block item. Called from {@code GuildModule.init}. The fields register when this
 * class loads, which {@link #init} triggers. The block cannot be broken in survival.
 */
public final class GuildBlocks {
    private static final ResourceKey<Block> NOTICE_BOARD_KEY =
            ResourceKey.create(Registries.BLOCK, Alina.id("notice_board"));
    private static final ResourceKey<Item> NOTICE_BOARD_ITEM_KEY =
            ResourceKey.create(Registries.ITEM, Alina.id("notice_board"));

    public static final NoticeBoardBlock NOTICE_BOARD = Registry.register(BuiltInRegistries.BLOCK, NOTICE_BOARD_KEY,
            new NoticeBoardBlock(BlockBehaviour.Properties.of()
                    .setId(NOTICE_BOARD_KEY)
                    .mapColor(MapColor.WOOD)
                    .strength(-1.0F, 3600000.0F)
                    .sound(SoundType.WOOD)
                    .noLootTable()));

    public static final BlockItem NOTICE_BOARD_ITEM = Registry.register(BuiltInRegistries.ITEM, NOTICE_BOARD_ITEM_KEY,
            new BlockItem(NOTICE_BOARD, new Item.Properties().setId(NOTICE_BOARD_ITEM_KEY).useBlockDescriptionPrefix()));

    private GuildBlocks() {}

    public static void init() {
        // Triggers the class load that registers the block and its item.
    }
}
