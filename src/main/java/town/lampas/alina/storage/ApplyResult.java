package town.lampas.alina.storage;

/**
 * The outcome of {@link AlinaStorage#applyOperation}. {@code fresh} is false when the operation id was already recorded,
 * and then nothing was applied. {@code reputation} is the character's reputation after the call.
 */
public record ApplyResult(boolean fresh, int reputation) {}
