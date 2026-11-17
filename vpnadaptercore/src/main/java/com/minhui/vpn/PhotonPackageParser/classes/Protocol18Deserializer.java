package com.minhui.vpn.PhotonPackageParser.classes;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Photon Protocol 18 参数解码器。
 *
 * 当前版本的游戏（1.32）UDP 5056 用的是 Protocol18 参数编码：
 * 参数头是 [ID 1B][Type 1B]，值按类型变长编码（Varint / ZigZag / 小端定长），
 * 数组长度、字符串长度都是 Varint；而项目原来的 Protocol16Deserializer 只认 V16 的
 * 单字节类型码（0x2A/0x62/0x73…），遇到 V18 的 0x11(Long16Positive)、0x43(ByteArray)
 * 这类类型码时要么解成 null、要么整包错位，Handler 永远拿不到数据，雷达也就没有东西可画。
 *
 * 参考实现：github.com/AutoDruid/photon-parser 的 internal/parameters/v18
 */
public class Protocol18Deserializer
{
    private static final String TAG = "Protocol18";

    private static final int MAX_COLLECTION_SIZE = 100_000;

    // Protocol18 类型码
    private static final int T_UNKNOWN = 0;
    private static final int T_BOOLEAN = 2;
    private static final int T_INT8 = 3;
    private static final int T_INT16 = 4;
    private static final int T_FLOAT32 = 5;
    private static final int T_FLOAT64 = 6;
    private static final int T_STRING = 7;
    private static final int T_NIL = 8;
    private static final int T_COMPRESSED_INT32 = 9;
    private static final int T_COMPRESSED_INT64 = 10;
    private static final int T_INT8_POSITIVE = 11;
    private static final int T_INT8_NEGATIVE = 12;
    private static final int T_INT16_POSITIVE = 13;
    private static final int T_INT16_NEGATIVE = 14;
    private static final int T_LONG8_POSITIVE = 15;
    private static final int T_LONG8_NEGATIVE = 16;
    private static final int T_LONG16_POSITIVE = 17;
    private static final int T_LONG16_NEGATIVE = 18;
    private static final int T_CUSTOM = 19;
    private static final int T_DICTIONARY = 20;
    private static final int T_HASHTABLE = 21;
    private static final int T_OBJECT_ARRAY = 23;
    private static final int T_BOOLEAN_FALSE = 27;
    private static final int T_BOOLEAN_TRUE = 28;
    private static final int T_SHORT_ZERO = 29;
    private static final int T_INT_ZERO = 30;
    private static final int T_LONG_ZERO = 31;
    private static final int T_FLOAT_ZERO = 32;
    private static final int T_DOUBLE_ZERO = 33;
    private static final int T_BYTE_ZERO = 34;
    private static final int T_ARRAY = 0x40;
    private static final int T_BOOLEAN_ARRAY = 0x42;
    private static final int T_BYTE_ARRAY = 0x43;
    private static final int T_SHORT_ARRAY = 0x44;
    private static final int T_FLOAT32_ARRAY = 0x45;
    private static final int T_FLOAT64_ARRAY = 0x46;
    private static final int T_STRING_ARRAY = 0x47;
    private static final int T_COMPRESSED_INT_ARRAY = 0x49;
    private static final int T_COMPRESSED_LONG_ARRAY = 0x4A;

    private Protocol18Deserializer()
    {
    }

    /** 事件数据：只有参数表（eventCode 已由调用方读掉）。 */
    public static HashMap<Object, Object> deserializeEventData(ByteBuffer input)
    {
        return readParameterTable(input);
    }

    /** 操作请求：只有参数表（operationCode 已由调用方读掉）。 */
    public static HashMap<Object, Object> deserializeOperationRequest(ByteBuffer input)
    {
        return readParameterTable(input);
    }

    /** 操作响应：操作码已读掉，其后是 returnCode(int16 LE) + 可选 debug 字符串 + 参数表。 */
    public static HashMap<Object, Object> deserializeOperationResponse(ByteBuffer input)
    {
        int start = input.position();

        // 变体 A：returnCode + debug 字符串(varint 长度) + 参数表
        try
        {
            HashMap<Object, Object> table = readResponseWithDebug(input);

            if (input.remaining() == 0)
            {
                return table;
            }
        }
        catch (Exception ignored)
        {
        }

        // 变体 B：没有 debug 字符串，returnCode 后直接是参数表
        input.position(start);

        try
        {
            HashMap<Object, Object> table = readResponseWithoutDebug(input);

            if (input.remaining() == 0)
            {
                return table;
            }
        }
        catch (Exception ignored)
        {
        }

        // 兜底：两种都不干净时，用"有 debug"的解析结果（至少能把大部分响应解出来）
        input.position(start);
        return readResponseWithDebug(input);
    }

    private static HashMap<Object, Object> readResponseWithDebug(ByteBuffer input)
    {
        readInt16LE(input);
        int debugLength = (int) readVarint(input);
        skip(input, debugLength);

        return readParameterTable(input);
    }

    private static HashMap<Object, Object> readResponseWithoutDebug(ByteBuffer input)
    {
        readInt16LE(input);

        return readParameterTable(input);
    }

    private static HashMap<Object, Object> readParameterTable(ByteBuffer input)
    {
        int count = input.get() & 0xFF;
        HashMap<Object, Object> table = new HashMap<>();

        for (int i = 0; i < count; i++)
        {
            int id = input.get() & 0xFF;
            int type = input.get() & 0xFF;
            table.put(id, readValue(input, type));
        }

        return table;
    }

    private static Object readValue(ByteBuffer input, int type)
    {
        switch (type)
        {
            case T_UNKNOWN:
            case T_NIL:
                return null;

            case T_BOOLEAN:
                return input.get() != 0;

            case T_INT8:
                return (int) (input.get() & 0xFF);

            case T_INT16:
                return (int) readInt16LE(input);

            case T_FLOAT32:
                return readFloat32LE(input);

            case T_FLOAT64:
                return (float) readFloat64LE(input);

            case T_STRING:
            {
                int length = (int) readVarint(input);
                return readString(input, length);
            }

            case T_COMPRESSED_INT32:
                return (int) readZigZagVarint(input);

            case T_COMPRESSED_INT64:
                return readZigZagVarint(input);

            case T_INT8_POSITIVE:
                return (int) (input.get() & 0xFF);

            case T_INT8_NEGATIVE:
                return -(int) (input.get() & 0xFF);

            case T_INT16_POSITIVE:
                return (int) readInt16LE(input);

            case T_INT16_NEGATIVE:
                return -(int) readInt16LE(input);

            case T_LONG8_POSITIVE:
                return (long) (input.get() & 0xFF);

            case T_LONG8_NEGATIVE:
                return -(long) (input.get() & 0xFF);

            case T_LONG16_POSITIVE:
                return (long) readInt16LE(input);

            case T_LONG16_NEGATIVE:
                return -(long) readInt16LE(input);

            case T_DICTIONARY:
                return readDictionary(input);

            case T_HASHTABLE:
                return readHashtable(input);

            case T_OBJECT_ARRAY:
                return readObjectArray(input);

            case T_BOOLEAN_FALSE:
                return Boolean.FALSE;

            case T_BOOLEAN_TRUE:
                return Boolean.TRUE;

            case T_SHORT_ZERO:
            case T_INT_ZERO:
            case T_BYTE_ZERO:
                return 0;

            case T_LONG_ZERO:
                return 0L;

            case T_FLOAT_ZERO:
            case T_DOUBLE_ZERO:
                return 0f;

            case T_ARRAY:
                return readObjectArray(input);

            case T_BOOLEAN_ARRAY:
                return readBooleanArray(input);

            case T_BYTE_ARRAY:
                return readByteArray(input);

            case T_SHORT_ARRAY:
                return readShortArray(input);

            case T_FLOAT32_ARRAY:
                return readFloat32Array(input);

            case T_FLOAT64_ARRAY:
                return readFloat64Array(input);

            case T_STRING_ARRAY:
                return readStringArray(input);

            case T_COMPRESSED_INT_ARRAY:
                return readCompressedIntArray(input);

            case T_COMPRESSED_LONG_ARRAY:
                return readCompressedLongArray(input);

            case T_CUSTOM:
            default:
                // 未知/自定义类型：无法确定长度，只能放弃这个包
                throw new IllegalArgumentException("Protocol18 unsupported type " + type);
        }
    }

    private static HashMap<Object, Object> readDictionary(ByteBuffer input)
    {
        int keyType = input.get() & 0xFF;
        int valueType = input.get() & 0xFF;
        int count = (int) readVarint(input);
        checkCount(count, 2);

        HashMap<Object, Object> map = new HashMap<>(Math.min(count, 1024));

        for (int i = 0; i < count; i++)
        {
            Object key = readValue(input, keyType);
            Object value = readValue(input, valueType);
            map.put(key, value);
        }

        return map;
    }

    /** Hashtable：count(varint) + 每项 [keyType][key][valueType][value]。 */
    private static HashMap<Object, Object> readHashtable(ByteBuffer input)
    {
        int count = (int) readVarint(input);
        checkCount(count, 4);

        HashMap<Object, Object> map = new HashMap<>(Math.min(count, 1024));

        for (int i = 0; i < count; i++)
        {
            int keyType = input.get() & 0xFF;
            Object key = readValue(input, keyType);
            int valueType = input.get() & 0xFF;
            Object value = readValue(input, valueType);
            map.put(key, value);
        }

        return map;
    }

    private static Object[] readObjectArray(ByteBuffer input)
    {
        int count = (int) readVarint(input);
        checkCount(count, 2);

        Object[] result = new Object[count];

        for (int i = 0; i < count; i++)
        {
            int type = input.get() & 0xFF;
            result[i] = readValue(input, type);
        }

        return result;
    }

    private static byte[] readByteArray(ByteBuffer input)
    {
        int count = (int) readVarint(input);
        checkCount(count, 1);

        byte[] bytes = new byte[count];
        input.get(bytes);
        return bytes;
    }

    private static boolean[] readBooleanArray(ByteBuffer input)
    {
        int count = (int) readVarint(input);
        checkCount(count, 1);

        int packedLength = (count + 7) / 8;
        checkRemaining(input, packedLength);

        boolean[] result = new boolean[count];
        byte[] packed = new byte[packedLength];
        input.get(packed);

        for (int i = 0; i < count; i++)
        {
            result[i] = (packed[i >> 3] & (1 << (i & 7))) != 0;
        }

        return result;
    }

    private static short[] readShortArray(ByteBuffer input)
    {
        int count = (int) readVarint(input);
        checkCount(count, 2);

        short[] result = new short[count];

        for (int i = 0; i < count; i++)
        {
            result[i] = (short) readInt16LE(input);
        }

        return result;
    }

    private static Object[] readFloat32Array(ByteBuffer input)
    {
        int count = (int) readVarint(input);
        checkCount(count, 4);

        Object[] result = new Object[count];

        for (int i = 0; i < count; i++)
        {
            result[i] = readFloat32LE(input);
        }

        return result;
    }

    private static Object[] readFloat64Array(ByteBuffer input)
    {
        int count = (int) readVarint(input);
        checkCount(count, 8);

        Object[] result = new Object[count];

        for (int i = 0; i < count; i++)
        {
            result[i] = (float) readFloat64LE(input);
        }

        return result;
    }

    private static String[] readStringArray(ByteBuffer input)
    {
        int count = (int) readVarint(input);
        checkCount(count, 1);

        String[] result = new String[count];

        for (int i = 0; i < count; i++)
        {
            int length = (int) readVarint(input);
            result[i] = readString(input, length);
        }

        return result;
    }

    private static int[] readCompressedIntArray(ByteBuffer input)
    {
        int count = (int) readVarint(input);
        checkCount(count, 1);

        int[] result = new int[count];

        for (int i = 0; i < count; i++)
        {
            result[i] = (int) readZigZagVarint(input);
        }

        return result;
    }

    private static long[] readCompressedLongArray(ByteBuffer input)
    {
        int count = (int) readVarint(input);
        checkCount(count, 1);

        long[] result = new long[count];

        for (int i = 0; i < count; i++)
        {
            result[i] = readZigZagVarint(input);
        }

        return result;
    }

    // ---------- 基础读取 ----------

    /** 7 位一组的无符号 Varint（LEB128）。 */
    private static long readVarint(ByteBuffer input)
    {
        long result = 0;
        int shift = 0;

        for (int i = 0; i < 10; i++)
        {
            if (!input.hasRemaining())
            {
                throw new IllegalArgumentException("truncated varint");
            }

            int b = input.get() & 0xFF;
            result |= (long) (b & 0x7F) << shift;

            if ((b & 0x80) == 0)
            {
                return result;
            }

            shift += 7;
        }

        throw new IllegalArgumentException("varint too long");
    }

    private static long readZigZagVarint(ByteBuffer input)
    {
        long raw = readVarint(input);
        return (raw >>> 1) ^ -(raw & 1L);
    }

    private static short readInt16LE(ByteBuffer input)
    {
        return (short) ((input.get() & 0xFF) | ((input.get() & 0xFF) << 8));
    }

    private static float readFloat32LE(ByteBuffer input)
    {
        int bits = (input.get() & 0xFF)
                | ((input.get() & 0xFF) << 8)
                | ((input.get() & 0xFF) << 16)
                | ((input.get() & 0xFF) << 24);
        return Float.intBitsToFloat(bits);
    }

    private static double readFloat64LE(ByteBuffer input)
    {
        long bits = 0;

        for (int i = 0; i < 8; i++)
        {
            bits |= (long) (input.get() & 0xFF) << (8 * i);
        }

        return Double.longBitsToDouble(bits);
    }

    private static String readString(ByteBuffer input, int length)
    {
        checkRemaining(input, length);

        byte[] bytes = new byte[length];
        input.get(bytes);

        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void skip(ByteBuffer input, int length)
    {
        checkRemaining(input, length);
        input.position(input.position() + length);
    }

    private static void checkRemaining(ByteBuffer input, int required)
    {
        if (required < 0 || required > input.remaining())
        {
            throw new IllegalArgumentException("Protocol18 truncated: need " + required
                    + " bytes, only " + input.remaining() + " left");
        }
    }

    private static void checkCount(int count, int minBytesPerItem)
    {
        if (count < 0 || count > MAX_COLLECTION_SIZE)
        {
            throw new IllegalArgumentException("Protocol18 bad collection size " + count);
        }

        if ((long) count * minBytesPerItem > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Protocol18 collection too large " + count);
        }
    }
}
