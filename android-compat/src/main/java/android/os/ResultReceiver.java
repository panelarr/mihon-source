// SPDX-License-Identifier: GPL-3.0-or-later

package android.os;

/** Delivers a result code and Bundle to {@link #onReceiveResult}, on the handler when one is given. */
public class ResultReceiver implements Parcelable {
    private final Handler handler;

    public ResultReceiver(Handler handler) {
        this.handler = handler;
    }

    public void send(int resultCode, Bundle resultData) {
        if (handler != null) handler.post(() -> onReceiveResult(resultCode, resultData));
        else onReceiveResult(resultCode, resultData);
    }

    protected void onReceiveResult(int resultCode, Bundle resultData) {}

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeValue(this);
    }
}
