// SPDX-License-Identifier: GPL-3.0-or-later

package android.os;

/** Android's Message, without pooling. */
public final class Message implements Parcelable {
    public int what;
    public int arg1;
    public int arg2;
    public Object obj;
    public Messenger replyTo;
    Handler target;
    Runnable callback;
    private Bundle data;

    public Message() {}

    public static Message obtain() {
        return new Message();
    }

    public static Message obtain(Message orig) {
        Message m = new Message();
        m.what = orig.what;
        m.arg1 = orig.arg1;
        m.arg2 = orig.arg2;
        m.obj = orig.obj;
        m.target = orig.target;
        m.callback = orig.callback;
        m.data = orig.data == null ? null : new Bundle(orig.data);
        return m;
    }

    public static Message obtain(Handler h) {
        Message m = new Message();
        m.target = h;
        return m;
    }

    public static Message obtain(Handler h, int what) {
        Message m = obtain(h);
        m.what = what;
        return m;
    }

    public static Message obtain(Handler h, int what, Object obj) {
        Message m = obtain(h, what);
        m.obj = obj;
        return m;
    }

    public static Message obtain(Handler h, int what, int arg1, int arg2) {
        Message m = obtain(h, what);
        m.arg1 = arg1;
        m.arg2 = arg2;
        return m;
    }

    public static Message obtain(Handler h, int what, int arg1, int arg2, Object obj) {
        Message m = obtain(h, what, arg1, arg2);
        m.obj = obj;
        return m;
    }

    public static Message obtain(Handler h, Runnable callback) {
        Message m = obtain(h);
        m.callback = callback;
        return m;
    }

    public void recycle() {}

    public Handler getTarget() {
        return target;
    }

    public void setTarget(Handler target) {
        this.target = target;
    }

    public Runnable getCallback() {
        return callback;
    }

    public Bundle getData() {
        if (data == null) data = new Bundle();
        return data;
    }

    public Bundle peekData() {
        return data;
    }

    public void setData(Bundle data) {
        this.data = data;
    }

    public void sendToTarget() {
        target.sendMessage(this);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {}

    @Override
    public String toString() {
        return "Message{what=" + what + ", arg1=" + arg1 + ", arg2=" + arg2 + ", obj=" + obj + "}";
    }
}
