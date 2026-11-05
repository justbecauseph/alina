package town.lampas.alina.guild.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.GuildSettings;
import town.lampas.alina.guild.GuildText;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.dialogue.api.DialogueAnchor;

/**
 * The Notice Board (ALINA-PLAN §8.4). Using it opens the content's board graph, anchored to the block: the conversation ends
 * when the player moves beyond {@code board_reach} blocks from it.
 */
public final class NoticeBoardBlock extends Block {
    public static final MapCodec<NoticeBoardBlock> CODEC = simpleCodec(NoticeBoardBlock::new);

    public NoticeBoardBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            open(serverPlayer, level, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        return useWithoutItem(state, level, pos, player, hit);
    }

    private static void open(ServerPlayer player, Level level, BlockPos pos) {
        GuildSettings settings = AlinaContent.guild().settings();
        DialogueAnchor anchor = new DialogueAnchor(level.dimension(), Vec3.atCenterOf(pos), settings.boardReach());
        if (!HaruhiServices.dialogue().openAt(player, settings.boardGraph(), anchor)) {
            player.sendSystemMessage(GuildText.boardMissing());
        }
    }
}
