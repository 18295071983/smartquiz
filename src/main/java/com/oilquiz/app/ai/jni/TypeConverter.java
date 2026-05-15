package com.oilquiz.app.ai.jni;

import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class TypeConverter {
    private static final String TAG = "TypeConverter";

    public static String jstringToString(Object jstring) {
        if (jstring == null) return null;
        try {
            return jstring.toString();
        } catch (Exception e) {
            Log.w(TAG, "Failed to convert jstring", e);
            return null;
        }
    }

    public static byte[] jbyteArrayToByteArray(byte[] jbyteArray) {
        if (jbyteArray == null) return null;
        return jbyteArray.clone();
    }

    public static byte[] byteArrayToJbyteArray(byte[] byteArray) {
        return byteArray.clone();
    }

    public static int[] jintArrayToIntArray(int[] jintArray) {
        if (jintArray == null) return null;
        return jintArray.clone();
    }

    public static int[] intArrayToJintArray(int[] intArray) {
        return intArray.clone();
    }

    public static long[] jlongArrayToLongArray(long[] jlongArray) {
        if (jlongArray == null) return null;
        return jlongArray.clone();
    }

    public static float[] jfloatArrayToFloatArray(float[] jfloatArray) {
        if (jfloatArray == null) return null;
        return jfloatArray.clone();
    }

    public static double[] jdoubleArrayToDoubleArray(double[] jdoubleArray) {
        if (jdoubleArray == null) return null;
        return jdoubleArray.clone();
    }

    public static boolean[] jbooleanArrayToBooleanArray(boolean[] jbooleanArray) {
        if (jbooleanArray == null) return null;
        return jbooleanArray.clone();
    }

    public static byte[] shortArrayToByteArray(short[] shortArray) {
        ByteBuffer buffer = ByteBuffer.allocate(shortArray.length * 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (short value : shortArray) {
            buffer.putShort(value);
        }
        return buffer.array();
    }

    public static short[] byteArrayToShortArray(byte[] byteArray) {
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        short[] shorts = new short[byteArray.length / 2];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = buffer.getShort();
        }
        return shorts;
    }

    public static byte[] intArrayToByteArray(int[] intArray) {
        ByteBuffer buffer = ByteBuffer.allocate(intArray.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (int value : intArray) {
            buffer.putInt(value);
        }
        return buffer.array();
    }

    public static int[] byteArrayToIntArray(byte[] byteArray) {
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int[] ints = new int[byteArray.length / 4];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = buffer.getInt();
        }
        return ints;
    }

    public static byte[] longArrayToByteArray(long[] longArray) {
        ByteBuffer buffer = ByteBuffer.allocate(longArray.length * 8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (long value : longArray) {
            buffer.putLong(value);
        }
        return buffer.array();
    }

    public static long[] byteArrayToLongArray(byte[] byteArray) {
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        long[] longs = new long[byteArray.length / 8];
        for (int i = 0; i < longs.length; i++) {
            longs[i] = buffer.getLong();
        }
        return longs;
    }

    public static byte[] floatArrayToByteArray(float[] floatArray) {
        ByteBuffer buffer = ByteBuffer.allocate(floatArray.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float value : floatArray) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    public static float[] byteArrayToFloatArray(byte[] byteArray) {
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[byteArray.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    public static byte[] stringToUtf8Bytes(String string) {
        return string.getBytes(Charset.forName("UTF-8"));
    }

    public static String utf8BytesToString(byte[] bytes) {
        return new String(bytes, Charset.forName("UTF-8"));
    }

    public static byte[] stringToUtf16Bytes(String string) {
        char[] charArray = string.toCharArray();
        ByteBuffer buffer = ByteBuffer.allocate(charArray.length * 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (char c : charArray) {
            buffer.putChar(c);
        }
        return buffer.array();
    }

    public static String utf16BytesToString(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        char[] chars = new char[bytes.length / 2];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = buffer.getChar();
        }
        return new String(chars);
    }

    public static byte[] packStringArray(List<String> strings) {
        int totalLength = 4;
        for (String str : strings) {
            totalLength += 4 + str.length();
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(strings.size());
        for (String str : strings) {
            byte[] bytes = str.getBytes(Charset.forName("UTF-8"));
            buffer.putInt(bytes.length);
            buffer.put(bytes);
        }

        return buffer.array();
    }

    public static List<String> unpackStringArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int count = buffer.getInt();
        List<String> strings = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            int length = buffer.getInt();
            byte[] strBytes = new byte[length];
            buffer.get(strBytes);
            strings.add(new String(strBytes, Charset.forName("UTF-8")));
        }

        return strings;
    }

    public static byte[] serializeFloatArray2D(float[][] array) {
        int rows = array.length;
        int cols = array[0].length;

        ByteBuffer buffer = ByteBuffer.allocate(8 + rows * cols * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(rows);
        buffer.putInt(cols);

        for (float[] row : array) {
            for (float value : row) {
                buffer.putFloat(value);
            }
        }

        return buffer.array();
    }

    public static float[][] deserializeFloatArray2D(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int rows = buffer.getInt();
        int cols = buffer.getInt();

        float[][] array = new float[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                array[i][j] = buffer.getFloat();
            }
        }

        return array;
    }
}

class NativeBuffer {
    private final ByteBuffer buffer;
    private long reference = 0;

    public NativeBuffer(int capacity) {
        this.buffer = ByteBuffer.allocateDirect(capacity);
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public long getAddress() {
        return reference;
    }

    public void setAddress(long addr) {
        this.reference = addr;
    }

    public int position() {
        return buffer.position();
    }

    public NativeBuffer position(int newPosition) {
        buffer.position(newPosition);
        return this;
    }

    public int limit() {
        return buffer.limit();
    }

    public NativeBuffer limit(int newLimit) {
        buffer.limit(newLimit);
        return this;
    }

    public int capacity() {
        return buffer.capacity();
    }

    public NativeBuffer clear() {
        buffer.clear();
        return this;
    }

    public NativeBuffer flip() {
        buffer.flip();
        return this;
    }

    public NativeBuffer rewind() {
        buffer.rewind();
        return this;
    }

    public NativeBuffer put(byte b) {
        buffer.put(b);
        return this;
    }

    public NativeBuffer putShort(short value) {
        buffer.putShort(value);
        return this;
    }

    public NativeBuffer putInt(int value) {
        buffer.putInt(value);
        return this;
    }

    public NativeBuffer putLong(long value) {
        buffer.putLong(value);
        return this;
    }

    public NativeBuffer putFloat(float value) {
        buffer.putFloat(value);
        return this;
    }

    public NativeBuffer putDouble(double value) {
        buffer.putDouble(value);
        return this;
    }

    public NativeBuffer putBytes(byte[] bytes, int offset, int length) {
        buffer.put(bytes, offset, length);
        return this;
    }

    public byte get() {
        return buffer.get();
    }

    public short getShort() {
        return buffer.getShort();
    }

    public int getInt() {
        return buffer.getInt();
    }

    public long getLong() {
        return buffer.getLong();
    }

    public float getFloat() {
        return buffer.getFloat();
    }

    public double getDouble() {
        return buffer.getDouble();
    }

    public byte[] getBytes(int length) {
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    public byte[] asByteArray() {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    public byte[] array() {
        return buffer.array();
    }

    public int remaining() {
        return buffer.remaining();
    }

    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }

    public static NativeBuffer allocate(int capacity) {
        return new NativeBuffer(capacity);
    }
}