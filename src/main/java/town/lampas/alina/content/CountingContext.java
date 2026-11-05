package town.lampas.alina.content;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import java.util.Set;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.api.ExtensionContext;

/** Passes everything to haruhi's extension context and counts the problems, so a reader can tell an entry failed. */
final class CountingContext implements ExtensionContext {
    private final ExtensionContext delegate;
    private int count;

    CountingContext(ExtensionContext delegate) {
        this.delegate = delegate;
    }

    int count() {
        return count;
    }

    @Override
    public Identifier id() {
        return delegate.id();
    }

    @Override
    public ContentBundle bundle() {
        return delegate.bundle();
    }

    @Override
    public Set<Identifier> npcIds() {
        return delegate.npcIds();
    }

    @Override
    public Set<Identifier> graphIds() {
        return delegate.graphIds();
    }

    @Override
    public DynamicOps<JsonElement> ops() {
        return delegate.ops();
    }

    @Override
    public void problem(String where, String message) {
        count++;
        delegate.problem(where, message);
    }
}
