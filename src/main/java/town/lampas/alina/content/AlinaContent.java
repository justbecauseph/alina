package town.lampas.alina.content;

import town.lampas.alina.Alina;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.api.ContentExtensionKey;
import town.lampas.haruhi.content.api.ContentExtensions;

/**
 * Owned by the content component. Registers Alina's two sections of the haruhi bundle, {@code alina:guild} and
 * {@code alina:achievements}, and reads them from the active bundle.
 */
public final class AlinaContent {
    public static final ContentExtensionKey<GuildContent> GUILD =
            new ContentExtensionKey<>(Alina.id("guild"), GuildContent.class);
    public static final ContentExtensionKey<AchievementsContent> ACHIEVEMENTS =
            new ContentExtensionKey<>(Alina.id("achievements"), AchievementsContent.class);

    private AlinaContent() {}

    /** Registers both extensions with haruhi. Call from {@code onInitialize}, after the criterion trigger types. */
    public static void init() {
        ServerLookups.init();
        ContentExtensions.register(GUILD.id(), GuildContent.class,
                (section, context) -> GuildReader.read(section, new CountingContext(context)));
        ContentExtensions.register(ACHIEVEMENTS.id(), AchievementsContent.class, AchievementsReader::read);
    }

    /** The active bundle's {@code alina:guild} section, or {@link GuildContent#EMPTY}. Server thread only. */
    public static GuildContent guild() {
        return HaruhiServices.content().active().extension(GUILD).orElse(GuildContent.EMPTY);
    }

    /** The active bundle's {@code alina:achievements} section, or {@link AchievementsContent#EMPTY}. */
    public static AchievementsContent achievements() {
        return HaruhiServices.content().active().extension(ACHIEVEMENTS).orElse(AchievementsContent.EMPTY);
    }
}
