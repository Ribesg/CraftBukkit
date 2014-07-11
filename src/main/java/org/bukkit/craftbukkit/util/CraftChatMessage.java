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

import org.bukkit.chat.*;
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

    private static class RichChatMessage {

        private final List<IChatBaseComponent> list = new ArrayList<IChatBaseComponent>();
        private final IChatBaseComponent[] output;
        private ChatModifier modifier = new ChatModifier();
        private int currentPartFirstIndex = 0;
        private boolean currentEndsWithLinebreak = false;

        private RichChatMessage(RichMessage message) {
            for (RichMessagePart part : message) {
                if (part.getType() == RichMessagePart.Type.CUSTOM) {
                    if (part.getText() != null) {
                        StringMessage parsed = new StringMessage(part.getText(), modifier);
                        this.modifier = parsed.getLastModifier();
                        Collections.addAll(list, parsed.getOutput());
                        currentEndsWithLinebreak = parsed.endsWithLinebreak();
                    } else if (part.getLocalizedText() != null) {
                        ChatMessage localizedMessage = new ChatMessage(part.getLocalizedText().getId(), part.getLocalizedText().getParameters());
                        localizedMessage.setChatModifier(modifier.clone());
                        list.add(localizedMessage);
                    } else {
                        // "Empty" part, ignore
                        continue;
                    }
                    applyHoverable(((CustomMessagePart) part).getTooltipLines());
                } else {
                    IChatBaseComponent component;
                    switch (part.getType()) {
                        case ACHIEVEMENT:
                            Achievement achievement = CraftStatistic.getNMSAchievement(((AchievementMessagePart) part).getAchievement());
                            component = achievement.e();
                            break;
                        case ITEM:
                            ItemStack item = CraftItemStack.asNMSCopy(((ItemMessagePart) part).getItem());
                            component = item.E();
                            break;
                        default:
                            throw new UnsupportedOperationException("Unknown RichMessagePart type: " + part.getType());
                    }
                    if (part.getText() != null) {
                        appendWithText(component, part.getText());
                    } else if (part.getLocalizedText() != null) {
                        appendWithLocalizedText(component, part.getLocalizedText());
                    } else {
                        append(component);
                    }
                }
                applyClickable(part.getClickAction());
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

        private void appendWithLocalizedText(IChatBaseComponent component, LocalizedText text) {
            ChatMessage localized = new ChatMessage(text.getId(), text.getParameters());
            localized.getChatModifier().a(component.getChatModifier().i());
            list.add(localized);
        }

        private void applyHoverable(String[] tooltipLines) {
            if (tooltipLines.length > 0) {
                ChatComponentText text = new ChatComponentText(tooltipLines[0]);
                for (int i = 1; i < tooltipLines.length; i++) {
                    text.a("\n").a(tooltipLines[i]);
                }
                ChatHoverable hoverable = new ChatHoverable(EnumHoverAction.SHOW_TEXT, text);
                for (int i = currentPartFirstIndex; i < list.size(); i++) {
                    list.get(i).getChatModifier().a(hoverable);
                }
            }
        }

        private void applyClickable(ClickAction clickAction) {
            if (clickAction != null) {
                ChatClickable clickable;
                switch (clickAction.getType()) {
                    case OPEN_URL:
                        clickable = new ChatClickable(EnumClickAction.OPEN_URL, ((OpenUrlAction) clickAction).getUrl());
                        break;
                    case CHAT:
                        clickable = new ChatClickable(EnumClickAction.RUN_COMMAND, ((ChatAction) clickAction).getText());
                        break;
                    case SUGGEST_CHAT:
                        clickable = new ChatClickable(EnumClickAction.SUGGEST_COMMAND, ((SuggestChatAction) clickAction).getText());
                        break;
                    default:
                        clickable = null;
                        break;
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

    public static IChatBaseComponent[] fromRichMessage(RichMessage message) {
        int hash = message.hashCode();
        if (cachedRichMessageHash == hash) {
            return cachedRichMessageConverted;
        } else {
            IChatBaseComponent[] nmsMessage = new RichChatMessage(message).getOutput();
            cachedRichMessageHash = hash;
            cachedRichMessageConverted = nmsMessage;
            return nmsMessage;
        }
    }

    private static int cachedRichMessageAsStringHash;
    private static String[] cachedRichMessageAsStringResult;

    public static String[] toColoredString(RichMessage message) {
        int hash = message.hashCode();
        if (cachedRichMessageAsStringHash == hash) {
            return cachedRichMessageAsStringResult;
        } else {
            StringBuilder builder = new StringBuilder();
            for (RichMessagePart part : message) {
                if (part.getText() != null) {
                    builder.append(part.getText());
                } else if (part.getLocalizedText() != null) {
                    LocalizedText localizedText = part.getLocalizedText();
                    builder.append(LocaleI18n.get(localizedText.getId(), localizedText.getParameters()));
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
