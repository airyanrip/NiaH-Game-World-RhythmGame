package djmax;

import java.awt.event.KeyEvent;

/**
 * Key-code to display text for lane labels. {@link KeyEvent#getKeyText} spells punctuation out as
 * words ("Semicolon", "Quote"), which is unreadable at a glance on a lane — this shows the actual
 * symbol instead for the common ones, falling back to the AWT name for everything else.
 */
final class KeyLabels {
    private KeyLabels() {
    }

    static String of(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_SEMICOLON -> ";";
            case KeyEvent.VK_QUOTE -> "'";
            case KeyEvent.VK_COMMA -> ",";
            case KeyEvent.VK_PERIOD -> ".";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_BACK_SLASH -> "\\";
            case KeyEvent.VK_OPEN_BRACKET -> "[";
            case KeyEvent.VK_CLOSE_BRACKET -> "]";
            case KeyEvent.VK_MINUS -> "-";
            case KeyEvent.VK_EQUALS -> "=";
            case KeyEvent.VK_BACK_QUOTE -> "`";
            default -> KeyEvent.getKeyText(keyCode);
        };
    }
}
