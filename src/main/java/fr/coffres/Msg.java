package fr.coffres;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.text.NumberFormat;
import java.util.Locale;

/** Chat messages. Text uses MiniMessage tags; values from players always go through Placeholder.unparsed. */
public final class Msg {

    public static final String PREFIX = "<gradient:#34C759:#FFCC00:#FF8D28:#FF383C:#CB30E0:#0088FF><b>Coffres</b></gradient> <dark_gray>›</dark_gray> ";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msg() {
    }

    public static Component parse(String text, TagResolver... resolvers) {
        return MM.deserialize(text, resolvers);
    }

    public static void send(Audience to, String text, TagResolver... resolvers) {
        to.sendMessage(MM.deserialize(PREFIX + text, resolvers));
    }

    public static void raw(Audience to, String text, TagResolver... resolvers) {
        to.sendMessage(MM.deserialize(text, resolvers));
    }

    public static void actionBar(Audience to, String text, TagResolver... resolvers) {
        to.sendActionBar(MM.deserialize(text, resolvers));
    }

    public static String number(long n) {
        return NumberFormat.getIntegerInstance(Locale.FRANCE).format(n).replace(' ', ' ').replace(' ', ' ');
    }
}
