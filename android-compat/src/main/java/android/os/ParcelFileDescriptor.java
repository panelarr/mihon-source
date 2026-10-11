// SPDX-License-Identifier: GPL-3.0-or-later

package android.os;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/** A file opened for reading and/or writing, as on Android. */
public class ParcelFileDescriptor implements Parcelable, Closeable {
    public static final int MODE_WORLD_READABLE = 0x00000001;
    public static final int MODE_WORLD_WRITEABLE = 0x00000002;
    public static final int MODE_READ_ONLY = 0x10000000;
    public static final int MODE_WRITE_ONLY = 0x20000000;
    public static final int MODE_READ_WRITE = 0x30000000;
    public static final int MODE_CREATE = 0x08000000;
    public static final int MODE_TRUNCATE = 0x04000000;
    public static final int MODE_APPEND = 0x02000000;

    private final RandomAccessFile file;

    private ParcelFileDescriptor(RandomAccessFile file) {
        this.file = file;
    }

    public static ParcelFileDescriptor open(File file, int mode) throws FileNotFoundException {
        boolean write = (mode & MODE_WRITE_ONLY) != 0;
        if (!write && !file.exists()) throw new FileNotFoundException(file + ": No such file or directory");
        if ((mode & MODE_CREATE) == 0 && !file.exists()) throw new FileNotFoundException(file + ": No such file or directory");
        RandomAccessFile raf = new RandomAccessFile(file, write ? "rw" : "r");
        try {
            if ((mode & MODE_TRUNCATE) != 0) raf.setLength(0);
            if ((mode & MODE_APPEND) != 0) raf.seek(raf.length());
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return new ParcelFileDescriptor(raf);
    }

    public FileDescriptor getFileDescriptor() {
        try {
            return file.getFD();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public long getStatSize() {
        try {
            return file.length();
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    @Override
    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeValue(this);
    }
}
