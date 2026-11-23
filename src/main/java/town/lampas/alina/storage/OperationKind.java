package town.lampas.alina.storage;

/**
 * What an {@link Operation} settles: a turn-in of a commission, the reward of an achievement or tier, or an NPC shop
 * trade. A {@code TRADE} operation carries a {@link OperationEffect.TradeSettled} effect.
 */
public enum OperationKind {
    TURN_IN,
    ACHIEVEMENT_REWARD,
    TRADE
}
