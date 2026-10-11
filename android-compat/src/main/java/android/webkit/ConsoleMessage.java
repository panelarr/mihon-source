// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

/** A message a page logged to its console. */
public class ConsoleMessage {
    public enum MessageLevel { TIP, LOG, WARNING, ERROR, DEBUG }

    private final String message;
    private final String sourceId;
    private final int lineNumber;
    private final MessageLevel level;

    public ConsoleMessage(String message, String sourceId, int lineNumber, MessageLevel msgLevel) {
        this.message = message;
        this.sourceId = sourceId;
        this.lineNumber = lineNumber;
        this.level = msgLevel;
    }

    public MessageLevel messageLevel() {
        return level;
    }

    public String message() {
        return message;
    }

    public String sourceId() {
        return sourceId;
    }

    public int lineNumber() {
        return lineNumber;
    }
}
