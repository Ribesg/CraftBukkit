package org.bukkit.craftbukkit.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.server.Achievement;
import net.minecraft.server.ChatClickable;
import net.minecraft.server.ChatComponentText;
import net.minecraft.server.ChatHoverable;
import net.minecraft.server.ChatMessage;
import net.minecraft.server.ChatModifier;
import net.minecraft.server.EnumChatFormat;
import net.minecraft.server.EnumClickAction;
import net.minecraft.server.EnumHoverAction;
import net.minecraft.server.IChatBaseComponent;
import net.minecraft.server.ItemStack;
import net.minecraft.server.LocaleI18n;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

import org.bukkit.chat.Message;
import org.bukkit.chat.Click;
import org.bukkit.chat.Hover;
import org.bukkit.chat.Part;
import org.bukkit.craftbukkit.CraftStatistic;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

public final class CraftChatMessage {
    private static class StringMessage {
        private static final Map<Character, EnumChatFormat> formatMap;
        private static final Pattern INCREMENTAL_PATTERN = Pattern.compile("(" + String.valueOf(org.bukkit.ChatColor.COLOR_CHAR) + "[0-9a-fk-or])|(\\n)|(?:(https?://[^ ][^ ]*?)(?=[\\.\\?!,;:]?(?:[ \\n]|$)))", Pattern.CASE_INSENSITIVE);

        static {
            Builder<Character, EnumChatFormat> builder = ImmutableMap.builder();
            for (EnumChatFormat format : EnumChatFormat.values()) {
                builder.put(Character.toLowerCase(format.getChar()), format);
            }
            formatMap = builder.build();
        }

        private final List<IChatBaseComponent> list = new ArrayList<IChatBaseComponent>();
        private IChatBaseComponent currentChatComponent = new ChatComponentText("");
        private ChatModifier modifier;
        private final IChatBaseComponent[] output;
        private int currentIndex;
        private final String message;
        private boolean endsWithLinebreak = false;

        private StringMessage(String message) {
            this(message, new ChatModifier());
        }

        private StringMessage(String message, ChatModifier prefixModifier) {
            this.message = message;
            this.modifier = prefixModifier.clone();
            if (message == null) {
                output = new IChatBaseComponent[] { currentChatComponent };
                return;
            }
            list.add(currentChatComponent);

            Matcher matcher = INCREMENTAL_PATTERN.matcher(message);
            String match = null;
            while (matcher.find()) {
                int groupId = 0;
                while ((match = matcher.group(++groupId)) == null) {
                    // NOOP
                }
                appendNewComponent(matcher.start(groupId));
                switch (groupId) {
                case 1:
                    EnumChatFormat format = formatMap.get(match.toLowerCase().charAt(1));
                    if (format == EnumChatFormat.RESET) {
                        modifier = new ChatModifier();
                    } else if (format.isFormat()) {
                        switch (format) {
                        case BOLD:
                            modifier.setBold(Boolean.TRUE);
                            break;
                        case ITALIC:
                            modifier.setItalic(Boolean.TRUE);
                            break;
                        case STRIKETHROUGH:
                            modifier.setStrikethrough(Boolean.TRUE);
                            break;
                        case UNDERLINE:
                            modifier.setUnderline(Boolean.TRUE);
                            break;
                        case RANDOM:
                            modifier.setRandom(Boolean.TRUE);
                            break;
                        default:
                            throw new AssertionError("Unexpected message format");
                        }
                    } else { // Color resets formatting
                        modifier = new ChatModifier().setColor(format);
                    }
                    break;
                case 2:
                    currentChatComponent = null;
                    endsWithLinebreak = true;
                    break;
                case 3:
                    modifier.setChatClickable(new ChatClickable(EnumClickAction.OPEN_URL, match));
                    appendNewComponent(matcher.end(groupId));
                    modifier.setChatClickable((ChatClickable) null);
                    endsWithLinebreak = false;
                }
                currentIndex = matcher.end(groupId);
            }

            if (currentIndex < message.length()) {
                appendNewComponent(message.length());
                endsWithLinebreak = false;
            }

            output = list.toArray(new IChatBaseComponent[0]);
        }

        private void appendNewComponent(int index) {
            if (index <= currentIndex) {
                return;
            }
            IChatBaseComponent addition = new ChatComponentText(message.substring(currentIndex, index)).setChatModifier(modifier);
            currentIndex = index;
            modifier = modifier.clone();
            if (currentChatComponent == null) {
                currentChatComponent = new ChatComponentText("");
                list.add(currentChatComponent);
            }
            currentChatComponent.addSibling(addition);
        }

        private IChatBaseComponent[] getOutput() {
            return output;
        }

        private boolean endsWithLinebreak() {
            return endsWithLinebreak;
        }

        private ChatModifier getLastModifier() {
            return modifier;
        }
    }

    private static class RichMessage {

        private final List<IChatBaseComponent> list = new ArrayList<IChatBaseComponent>();
        private final IChatBaseComponent[] output;
        private ChatModifier modifier = new ChatModifier();
        private int currentPartFirstIndex = 0;
        private boolean currentEndsWithLinebreak = false;

        private RichMessage(Message message) {
            for (Part part : message) {
                if (part.getHover() != null && part.getHover().getType() != Hover.Type.SHOW_TEXT) {
                    IChatBaseComponent component;
                    Hover hover = part.getHover();
                    if (hover.getType() == Hover.Type.SHOW_ACHIEVEMENT) {
                        Achievement achievement = CraftStatistic.getNMSAchievement(hover.getAchievement());
                        component = achievement.e(); // TODO Should be ?
                    } else if (hover.getType() == Hover.Type.SHOW_ITEM) {
                        ItemStack item = CraftItemStack.asNMSCopy(hover.getItem());
                        component = item.E(); // TODO Should be ?
                    } else {
                        throw new UnsupportedOperationException("Unkown hover object type: " + hover.getType());
                    }
                    if (part.isLocalizedText()) {
                        appendWithLocalizedText(component, part.getText(), part.getLocalizedTextParameters());
                    } else if (part.getText() != null) {
                        appendWithText(component, part.getText());
                    } else {
                        append(component);
                    }
                } else {
                    if (part.isLocalizedText()) {
                        ChatMessage localizedMessage = new ChatMessage(part.getText(), part.getLocalizedTextParameters());
                        localizedMessage.setChatModifier(modifier.clone());
                        list.add(localizedMessage);
                    } else if (part.getText() != null) {
                        StringMessage parsed = new StringMessage(part.getText(), modifier);
                        this.modifier = parsed.getLastModifier();
                        Collections.addAll(list, parsed.getOutput());
                        currentEndsWithLinebreak = parsed.endsWithLinebreak();
                    } else {
                        // TODO Empty part, should not be impossible, what do we do?
                        continue;
                    }
                    if (part.getHover() != null) {
                        applyHoverLines(part.getHover().getText());
                    }
                }
                applyClick(part.getClickAction());
                mergeWithPreviousPart();
                currentPartFirstIndex = list.size();
            }

            output = list.toArray(new IChatBaseComponent[list.size()]);
        }

        private void append(IChatBaseComponent... components) {
            Collections.addAll(list, components);
        }

        private void appendWithText(IChatBaseComponent component, String text) {
            ChatHoverable originalHover = component.getChatModifier().i();
            StringMessage parsed = new StringMessage(text, modifier);
            this.modifier = parsed.getLastModifier();
            IChatBaseComponent[] components = parsed.getOutput();
            for (IChatBaseComponent c : components) {
                c.getChatModifier().a(originalHover);
            }
            Collections.addAll(list, components);
            currentEndsWithLinebreak = parsed.endsWithLinebreak();
        }

        private void appendWithLocalizedText(IChatBaseComponent component, String id, String[] parameters) {
            ChatMessage localized = new ChatMessage(id, parameters);
            localized.getChatModifier().a(component.getChatModifier().i());
            list.add(localized);
        }

        private void applyHoverLines(String[] hoverLines) {
            // TODO hoverLines should never have length==0, do we still check?
            if (hoverLines != null && hoverLines.length > 0) {
                ChatComponentText text = new ChatComponentText(hoverLines[0]);
                for (int i = 1; i < hoverLines.length; i++) {
                    text.a("\n").a(hoverLines[i]);
                }
                ChatHoverable hoverable = new ChatHoverable(EnumHoverAction.SHOW_TEXT, text);
                for (int i = currentPartFirstIndex; i < list.size(); i++) {
                    list.get(i).getChatModifier().a(hoverable);
                }
            }
        }

        private void applyClick(Click clickAction) {
            if (clickAction != null) {
                ChatClickable clickable;
                switch (clickAction.getType()) {
                    case OPEN_URL:
                        clickable = new ChatClickable(EnumClickAction.OPEN_URL, clickAction.getText());
                        break;
                    case SEND_TEXT:
                        clickable = new ChatClickable(EnumClickAction.RUN_COMMAND, clickAction.getText());
                        break;
                    case SET_TEXT:
                        clickable = new ChatClickable(EnumClickAction.SUGGEST_COMMAND, clickAction.getText());
                        break;
                    default:
                        throw new UnsupportedOperationException("Unknown Click type: " + clickAction.getType());
                }
                for (int i = currentPartFirstIndex; i < list.size(); i++) {
                    list.get(i).getChatModifier().setChatClickable(clickable);
                }
            }
        }

        private void mergeWithPreviousPart() {
            // Merge first new one with last previous one to prevent unwanted linebreak
            if (currentPartFirstIndex != 0 && !currentEndsWithLinebreak) {
                list.get(currentPartFirstIndex - 1).addSibling(list.remove(currentPartFirstIndex));
            }
        }

        private IChatBaseComponent[] getOutput() {
            return output;
        }
    }

    private static int cachedStringMessageHash = 0;
    private static IChatBaseComponent[] cachedStringMessageConverted;

    public static IChatBaseComponent[] fromString(String message) {
        int hash = message.hashCode();
        if (cachedStringMessageHash == hash) {
            return cachedStringMessageConverted;
        } else {
            IChatBaseComponent[] nmsMessage = new StringMessage(message).getOutput();
            cachedStringMessageHash = hash;
            cachedStringMessageConverted = nmsMessage;
            return nmsMessage;
        }
    }

    private static int cachedRichMessageHash = 0;
    private static IChatBaseComponent[] cachedRichMessageConverted;

    public static IChatBaseComponent[] fromMessage(Message message) {
        int hash = message.hashCode();
        if (cachedRichMessageHash == hash) {
            return cachedRichMessageConverted;
        } else {
            IChatBaseComponent[] nmsMessage = new RichMessage(message).getOutput();
            cachedRichMessageHash = hash;
            cachedRichMessageConverted = nmsMessage;
            return nmsMessage;
        }
    }

    private static int cachedRichMessageAsStringHash;
    private static String[] cachedRichMessageAsStringResult;

    public static String[] toColoredString(Message message) {
        int hash = message.hashCode();
        if (cachedRichMessageAsStringHash == hash) {
            return cachedRichMessageAsStringResult;
        } else {
            StringBuilder builder = new StringBuilder();
            for (Part part : message) {
                if (!part.isLocalizedText()) {
                    builder.append(part.getText());
                } else {
                    builder.append(LocaleI18n.get(part.getText(), part.getLocalizedTextParameters()));
                }
            }
            String[] result = builder.toString().split("\n");
            cachedRichMessageAsStringHash = hash;
            cachedRichMessageAsStringResult = result;
            return result;
        }
    }

    private CraftChatMessage() {
    }
}
