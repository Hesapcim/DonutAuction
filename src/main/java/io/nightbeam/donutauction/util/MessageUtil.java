package io.nightbeam.donutauction.util;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

public final class MessageUtil {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final FileConfiguration configuration;
    private final String prefix;

    public MessageUtil(FileConfiguration configuration, boolean usePrefix) {
        this.configuration = configuration;
        this.prefix = usePrefix ? configuration.getString("prefix", "") : "";
    }

    public void send(CommandSender sender, String path, String fallback, Placeholder... placeholders) {
        sender.sendMessage(parse(prefix + text(path, fallback, placeholders)));
    }

    public void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(parse(prefix + message));
    }

    public String text(String path, String fallback, Placeholder... placeholders) {
        return applyPlaceholders(configuration.getString(path, fallback), placeholders);
    }

    public List<String> textList(String path, List<String> fallback, Placeholder... placeholders) {
        List<String> values = configuration.isList(path) ? configuration.getStringList(path) : fallback;
        return values.stream().map(value -> applyPlaceholders(value, placeholders)).toList();
    }

    public Component component(String path, String fallback, Placeholder... placeholders) {
        return parse(text(path, fallback, placeholders));
    }

    public List<Component> components(String path, List<String> fallback, Placeholder... placeholders) {
        return textList(path, fallback, placeholders).stream().map(this::parse).toList();
    }

    public Component parse(String text) {
        return disableItalic(SERIALIZER.deserialize(text == null ? "" : text));
    }

    public static Placeholder placeholder(String key, Object value) {
        return new Placeholder("%" + key + "%", Objects.toString(value, ""));
    }

    private String applyPlaceholders(String value, Placeholder... placeholders) {
        String formatted = value == null ? "" : value;
        for (Placeholder placeholder : placeholders) {
            formatted = formatted.replace(placeholder.token(), placeholder.value());
        }
        return formatted;
    }

    private Component disableItalic(Component component) {
        List<Component> children = component.children().stream().map(this::disableItalic).toList();
        return component.children(children).decoration(TextDecoration.ITALIC, false);
    }

    public record Placeholder(String token, String value) {
    }
}
