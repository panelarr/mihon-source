// SPDX-License-Identifier: GPL-3.0-or-later

package android.app;

import java.lang.management.ManagementFactory;

/** The memory queries of Android's ActivityManager, answered from the JVM and the OS. */
public class ActivityManager {
    public ActivityManager() {}

    public void getMemoryInfo(MemoryInfo outInfo) {
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.maxMemory();
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            total = os.getTotalMemorySize();
            outInfo.availMem = os.getFreeMemorySize();
        } else {
            outInfo.availMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        }
        outInfo.totalMem = total;
        outInfo.threshold = total / 16;
        outInfo.lowMemory = outInfo.availMem < outInfo.threshold;
    }

    public int getMemoryClass() {
        return (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    public int getLargeMemoryClass() {
        return getMemoryClass();
    }

    public boolean isLowRamDevice() {
        return false;
    }

    public static boolean isRunningInTestHarness() {
        return false;
    }

    public static class MemoryInfo implements android.os.Parcelable {
        public long availMem;
        public long totalMem;
        public long threshold;
        public boolean lowMemory;

        public MemoryInfo() {}

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(android.os.Parcel dest, int flags) {}
    }
}
