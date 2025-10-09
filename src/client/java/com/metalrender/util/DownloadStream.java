package com.metalrender.util;

import java.util.function.LongConsumer;

public class DownloadStream {
    public void download(long deviceAddress, int size, LongConsumer callback) {
        // TODO: map and read back; stub invokes callback with 0
        callback.accept(0L);
    }
}
