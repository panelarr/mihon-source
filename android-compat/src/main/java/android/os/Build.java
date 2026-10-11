// SPDX-License-Identifier: GPL-3.0-or-later

package android.os;

/** Device information. The host poses as a recent stock Android 14 phone. */
public class Build {
    public static final String UNKNOWN = "unknown";
    public static final String ID = "UQ1A.240205.004";
    public static final String DISPLAY = ID;
    public static final String PRODUCT = "panther";
    public static final String DEVICE = "panther";
    public static final String BOARD = "panther";
    public static final String MANUFACTURER = "Google";
    public static final String BRAND = "google";
    public static final String MODEL = "Pixel 7";
    public static final String BOOTLOADER = UNKNOWN;
    public static final String HARDWARE = "panther";
    public static final String SERIAL = UNKNOWN;
    public static final String[] SUPPORTED_ABIS = {"arm64-v8a"};
    public static final String[] SUPPORTED_64_BIT_ABIS = {"arm64-v8a"};
    public static final String[] SUPPORTED_32_BIT_ABIS = {};
    public static final String CPU_ABI = "arm64-v8a";
    public static final String CPU_ABI2 = "";
    public static final String TYPE = "user";
    public static final String TAGS = "release-keys";
    public static final String FINGERPRINT = "google/panther/panther:14/UQ1A.240205.004/11269751:user/release-keys";
    public static final long TIME = 1_706_745_600_000L;
    public static final String USER = "android-build";
    public static final String HOST = "build-host";

    public Build() {}

    public static String getSerial() {
        return UNKNOWN;
    }

    public static String getRadioVersion() {
        return null;
    }

    public static class VERSION {
        public static final String INCREMENTAL = "11269751";
        public static final String RELEASE = "14";
        public static final String RELEASE_OR_CODENAME = RELEASE;
        public static final String BASE_OS = "";
        public static final String SECURITY_PATCH = "2024-02-05";
        @Deprecated
        public static final String SDK = "34";
        public static final int SDK_INT = 34;
        public static final int PREVIEW_SDK_INT = 0;
        public static final String CODENAME = "REL";

        public VERSION() {}
    }

    public static class VERSION_CODES {
        public static final int CUR_DEVELOPMENT = 10000;
        public static final int BASE = 1;
        public static final int KITKAT = 19;
        public static final int LOLLIPOP = 21;
        public static final int LOLLIPOP_MR1 = 22;
        public static final int M = 23;
        public static final int N = 24;
        public static final int N_MR1 = 25;
        public static final int O = 26;
        public static final int O_MR1 = 27;
        public static final int P = 28;
        public static final int Q = 29;
        public static final int R = 30;
        public static final int S = 31;
        public static final int S_V2 = 32;
        public static final int TIRAMISU = 33;
        public static final int UPSIDE_DOWN_CAKE = 34;
        public static final int VANILLA_ICE_CREAM = 35;
        public static final int BAKLAVA = 36;

        public VERSION_CODES() {}
    }
}
