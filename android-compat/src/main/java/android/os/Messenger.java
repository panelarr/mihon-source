// SPDX-License-Identifier: GPL-3.0-or-later

package android.os;

/** Android's Messenger, sending to a local Handler. */
public final class Messenger implements Parcelable {
    private final Handler target;

    public Messenger(Handler target) {
        this.target = target;
    }

    public void send(Message message) {
        target.sendMessage(message);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {}
}
