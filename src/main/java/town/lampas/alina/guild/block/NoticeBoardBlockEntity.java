package town.lampas.alina.guild.block;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The Notice Board's block entity (ALINA-PLAN §8.4): the id of the board the block opens, or none. It is saved with the
 * chunk. It has no ticker, because it does nothing on a tick.
 */
public final class NoticeBoardBlockEntity extends BlockEntity {
    private static final String BOARD_KEY = "board";

    private Optional<Identifier> board = Optional.empty();

    public NoticeBoardBlockEntity(BlockPos pos, BlockState state) {
        super(GuildBlocks.NOTICE_BOARD_ENTITY, pos, state);
    }

    /** The board the block opens, or empty when none is assigned. */
    public Optional<Identifier> board() {
        return board;
    }

    /** Assigns the board, or clears it with an empty value, and marks the chunk as changed. */
    public void setBoard(Optional<Identifier> board) {
        this.board = Objects.requireNonNull(board, "board");
        setChanged();
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        board = input.read(BOARD_KEY, Identifier.CODEC);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        board.ifPresent(id -> output.store(BOARD_KEY, Identifier.CODEC, id));
    }
}
