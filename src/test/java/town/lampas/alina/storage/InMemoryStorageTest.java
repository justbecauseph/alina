package town.lampas.alina.storage;

/** Runs the storage contract against {@link InMemoryStorage}. */
class InMemoryStorageTest extends AlinaStorageContractTest {
    @Override
    protected AlinaStorage createStorage() {
        return new InMemoryStorage();
    }
}
