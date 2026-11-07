package town.lampas.alina.guild.block;

import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.Board;
import town.lampas.alina.content.GuildContent;
import town.lampas.alina.guild.GuildText;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.dialogue.api.DialogueAnchor;

/**
 * The Notice Board (ALINA-PLAN §8.4). Using it opens the graph of its board, anchored to the block: the conversation ends
 * when the player moves beyond the board's reach from it. A player farther than the reach is told to step closer. The
 * board is the one the block entity names. A block with none opens the content's only board, and with several boards it is
 * reported as unassigned. A board id the content lacks, or a graph missing from the active content, is reported as missing.
 */
public final class NoticeBoardBlock extends BaseEntityBlock {
    public static final MapCodec<NoticeBoardBlock> CODEC = simpleCodec(NoticeBoardBlock::new);

    public NoticeBoardBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NoticeBoardBlockEntity(pos, state);
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
        GuildContent content = AlinaContent.guild();
        Optional<Identifier> assigned = level.getBlockEntity(pos) instanceof NoticeBoardBlockEntity entity
                ? entity.board()
                : Optional.empty();
        Board board;
        if (assigned.isPresent()) {
            board = content.boards().get(assigned.get());
            if (board == null) {
                player.sendSystemMessage(GuildText.boardMissing());
                return;
            }
        } else if (content.boards().size() == 1) {
            board = content.boards().values().iterator().next();
        } else {
            player.sendSystemMessage(GuildText.boardUnassigned());
            return;
        }
        DialogueAnchor anchor = new DialogueAnchor(level.dimension(), Vec3.atCenterOf(pos), board.reach());
        if (!anchor.holds(player)) {
            player.sendSystemMessage(GuildText.tooFar());
            return;
        }
        // Past the reach check, openAt is false when the board graph is missing from the active content.
        if (!HaruhiServices.dialogue().openAt(player, board.graph(), anchor)) {
            player.sendSystemMessage(GuildText.boardMissing());
        }
    }
}
