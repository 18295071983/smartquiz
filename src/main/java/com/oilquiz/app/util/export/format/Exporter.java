package com.oilquiz.app.util.export.format;

import java.io.IOException;
import java.io.OutputStream;

public interface Exporter {
    void export(Object data, OutputStream outputStream) throws IOException;
    String getFileExtension();
    String getMimeType();
}
