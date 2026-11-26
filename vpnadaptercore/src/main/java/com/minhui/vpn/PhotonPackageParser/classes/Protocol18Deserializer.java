package com.minhui.vpn.PhotonPackageParser.classes;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Photon Protocol 18 参数解码器。
 *
 * <p>1:1 移植自 PC 参考实现 {@code Albion-Online-OpenRadar/internal/photon/}：
 * <ul>
 *   <li>{@code typecodes.go} 类型码表</li>
 *   <li>{@code deserializer.go} 反序列化（含 custom/slim custom、typed array、dictionary/hashtable）</li>
 *   <li>{@code readers.go} varint / zigzag / 小端定长读取</li>
 * </ul>
 *
 * <p>关键点（旧实现缺失，导致 Join 响应报 "Protocol18 unsupported type 211/83" 并中断解析）：
 * <ol>
 *   <li>Custom 类型：type 19 先读一个 customTypeID 字节，再读 varint 长度 + 数据；
 *       type &gt;= 0x80 是 slim custom，直接读 varint 长度 + 数据。旧实现把 211(0xD3)/83(0x53)
 *       当成未知类型抛异常。</li>
 *   <li>OperationResponse 的 debug 槽是"带类型码的值"（通常是 String 或 String[]，
 *       也见过 null），旧实现把它当成 varint 长度直接 skip，整体错位后才出现莫名的
 *       未知类型字节。</li>
 *   <li>参数表条数、集合长度都是 LEB128 varint，不是单字节（条数 &gt;= 128 时必须多字节）。</li>
 *   <li>0x40 是"嵌套数组"(count + 元素类型 + N 个值)，0x42..0x4A 才是 typed array。</li>
 * </ol>
 */
public final class Protocol18Deserializer
{
    // ---- Protocol18 类型码（internal/photon/typecodes.go） ----
    private static final int T_UNKNOWN = 0;
    private static final int T_BOOLEAN = 2;
    private static final int T_BYTE = 3;
    private static final int T_SHORT = 4;
    private static final int T_FLOAT = 5;
    private static final int T_DOUBLE = 6;
    private static final int T_STRING = 7;
    private static final int T_NULL = 8;
    private static final int T_COMPRESSED_INT = 9;
    private static final int T_COMPRESSED_LONG = 10;
    private static final int T_INT1 = 11;
    private static final int T_INT1_NEG = 12;
    private static final int T_INT2 = 13;
    private static final int T_INT2_NEG = 14;
    private static final int T_LONG1 = 15;
    private static final int T_LONG1_NEG = 16;
    private static final int T_LONG2 = 17;
    private static final int T_LONG2_NEG = 18;
    private static final int T_CUSTOM = 19;
    private static final int T_DICTIONARY = 20;
    private static final int T_HASHTABLE = 21;
    private static final int T_OBJECT_ARRAY = 23;
    private static final int T_OPERATION_REQUEST = 24;
    private static final int T_OPERATION_RESPONSE = 25;
    private static final int T_EVENT_DATA = 26;
    private static final int T_BOOL_FALSE = 27;
    private static final int T_BOOL_TRUE = 28;
    private static final int T_SHORT_ZERO = 29;
    private static final int T_INT_ZERO = 30;
    private static final int T_LONG_ZERO = 31;
    private static final int T_FLOAT_ZERO = 32;
    private static final int T_DOUBLE_ZERO = 33;
    private static final int T_BYTE_ZERO = 34;
    private static final int T_ARRAY = 0x40;
    private static final int T_SLIM_CUSTOM_BASE = 0x80;

    /** 与 PC maxArraySize 一致：挡住损坏长度字段导致的巨量分配。 */
    private static final int MAX_ARRAY_SIZE = 65536;

    private Protocol18Deserializer()
    {
    }

    /** 事件数据：只剩参数表（事件码已由调用方读走）。 */
    public static HashMap<Object, Object> deserializeEventData(ByteBuffer input)
    {
        return readParameterTable(input);
    }

    /** 操作请求：只剩参数表（操作码已由调用方读走）。 */
    public static HashMap<Object, Object> deserializeOperationRequest(ByteBuffer input)
    {
        return readParameterTable(input);
    }

    /**
     * 操作响应：操作码已由调用方读走，其后是 returnCode(int16 LE)
     * + debug 槽（带类型码的值）+ 参数表。
     *
     * <p>Albion 会在市场订单响应的 debug 槽里塞一个 string[]，PC 端把它落到
     * parameters[0] 供下游 handler 使用，这里保持一致。
     */
    public static HashMap<Object, Object> deserializeOperationResponse(ByteBuffer input)
    {
        delimit(input, 3, "operation response");
        readInt16(input);

        Object[] marketOrders = null;

        if (input.hasRemaining())
        {
            int debugType = readByte(input);
            Object debugValue = deserialize(input, debugType);

            if (debugValue instanceof Object[])
            {
                marketOrders = (Object[]) debugValue;
            }
        }

        HashMap<Object, Object> params = readParameterTable(input);

        if (marketOrders != null)
        {
            params.put(0, marketOrders);
        }

        return params;
    }

    private static HashMap<Object, Object> readParameterTable(ByteBuffer input)
    {
        HashMap<Object, Object> table = new HashMap<>();

        int count = (int) readVarint(input);

        if (count < 0 || count > MAX_ARRAY_SIZE || count > input.remaining())
        {
            return table;
        }

        for (int i = 0; i < count && input.hasRemaining(); i++)
        {
            try
            {
                int id = readByte(input);

                if (!input.hasRemaining())
                {
                    break;
                }

                int type = readByte(input);
                table.put(id, deserialize(input, type));
            }
            catch (RuntimeException truncated)
            {
                // 参考 PC readParameterTable：截断时返回已解析的部分，不丢弃整包。
                break;
            }
        }

        return table;
    }

    private static Object deserialize(ByteBuffer input, int type)
    {
        if (type >= T_SLIM_CUSTOM_BASE)
        {
            return readByteArrayPayload(input, false);
        }

        switch (type)
        {
            case T_UNKNOWN:
            case T_NULL:
                return null;

            case T_BOOLEAN:
                return readByte(input) != 0;

            case T_BYTE:
                return readByte(input);

            case T_SHORT:
                return (int) readInt16(input);

            case T_FLOAT:
                return readFloat32(input);

            case T_DOUBLE:
                return (float) readFloat64(input);

            case T_STRING:
                return readString(input);

            case T_COMPRESSED_INT:
                return (int) readZigZagVarint(input);

            case T_COMPRESSED_LONG:
                return readZigZagVarint(input);

            case T_INT1:
                return readByte(input);

            case T_INT1_NEG:
                return -readByte(input);

            case T_INT2:
                return (int) readUInt16(input);

            case T_INT2_NEG:
                return -(int) readUInt16(input);

            case T_LONG1:
                return (long) readByte(input);

            case T_LONG1_NEG:
                return -(long) readByte(input);

            case T_LONG2:
                return (long) readUInt16(input);

            case T_LONG2_NEG:
                return -(long) readUInt16(input);

            case T_CUSTOM:
                return readByteArrayPayload(input, true);

            case T_DICTIONARY:
            case T_HASHTABLE:
                return readDictionary(input);

            case T_OBJECT_ARRAY:
                return readObjectArray(input);

            case T_OPERATION_REQUEST:
                return readOperationRequestInner(input);

            case T_OPERATION_RESPONSE:
                return readOperationResponseInner(input);

            case T_EVENT_DATA:
                return readEventDataInner(input);

            case T_BOOL_FALSE:
                return Boolean.FALSE;

            case T_BOOL_TRUE:
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
                return readNestedArray(input);

            default:
                if ((type & T_ARRAY) == T_ARRAY)
                {
                    return readTypedArray(input, type & ~T_ARRAY);
                }

                // 未知类型无法判断长度，继续解析只会整体错位：
                // 抛出去由 PhotonPacketParser 丢弃这一条消息，后续消息不受影响。
                throw new IllegalArgumentException("Protocol18 unsupported type " + type);
        }
    }

    // ---------------- 复合类型 ----------------

    /**
     * Custom 字节数组。type 19 时 gpType &lt; 0x80 需要先读一个 customTypeID 字节；
     * slim custom（type &gt;= 0x80）没有这个字节。
     */
    private static byte[] readByteArrayPayload(ByteBuffer input, boolean withTypeId)
    {
        if (withTypeId)
        {
            readByte(input);
        }

        int size = (int) readVarint(input);

        if (size < 0 || size > MAX_ARRAY_SIZE || size > input.remaining())
        {
            return null;
        }

        byte[] data = new byte[size];
        input.get(data);
        return data;
    }

    /**
     * Dictionary / Hashtable：keyType、valueType、count，然后逐项 key/value。
     * 类型码为 0 时表示每一项自带类型码（PC deserializeDictionary）。
     */
    private static HashMap<Object, Object> readDictionary(ByteBuffer input)
    {
        int keyType = readByte(input);
        int valueType = readByte(input);
        int count = (int) readVarint(input);

        HashMap<Object, Object> map = new HashMap<>();

        if (count < 0 || count > MAX_ARRAY_SIZE || count > input.remaining())
        {
            return map;
        }

        for (int i = 0; i < count && input.hasRemaining(); i++)
        {
            try
            {
                int kt = keyType;

                if (kt == 0)
                {
                    kt = readByte(input);
                }

                int vt = valueType;

                if (vt == 0)
                {
                    vt = readByte(input);
                }

                Object key = deserialize(input, kt);
                Object value = deserialize(input, vt);

                if (isComparableKey(key))
                {
                    map.put(key, value);
                }
                else
                {
                    map.put("UNHASHABLE_" + i + "_" + (key == null ? "null" : key.getClass().getSimpleName()), value);
                }
            }
            catch (RuntimeException truncated)
            {
                break;
            }
        }

        return map;
    }

    private static Object[] readObjectArray(ByteBuffer input)
    {
        int size = (int) readVarint(input);

        if (size < 0 || size > MAX_ARRAY_SIZE || size > input.remaining())
        {
            return null;
        }

        Object[] result = new Object[size];

        for (int i = 0; i < size && input.hasRemaining(); i++)
        {
            int type = readByte(input);
            result[i] = deserialize(input, type);
        }

        return result;
    }

    /** 0x40：count + 统一元素类型 + N 个值。 */
    private static Object[] readNestedArray(ByteBuffer input)
    {
        int size = (int) readVarint(input);

        if (size < 0 || size > MAX_ARRAY_SIZE || size > input.remaining())
        {
            return null;
        }

        int elementType = readByte(input);
        Object[] result = new Object[size];

        for (int i = 0; i < size && input.hasRemaining(); i++)
        {
            result[i] = deserialize(input, elementType);
        }

        return result;
    }

    /** 0x41..0x7F：typed array，元素类型 = type &amp; ~0x40。 */
    private static Object readTypedArray(ByteBuffer input, int elementType)
    {
        int size = (int) readVarint(input);

        if (size < 0 || size > MAX_ARRAY_SIZE)
        {
            return null;
        }

        switch (elementType)
        {
            case T_BOOLEAN:
            {
                boolean[] result = new boolean[size];
                int packedLength = (size + 7) / 8;
                byte[] packed = new byte[packedLength];
                input.get(packed);

                for (int i = 0; i < size; i++)
                {
                    result[i] = (packed[i >> 3] & (1 << (i & 7))) != 0;
                }

                return result;
            }

            case T_BYTE:
            {
                byte[] result = new byte[size];
                input.get(result);
                return result;
            }

            case T_SHORT:
            {
                short[] result = new short[size];

                for (int i = 0; i < size; i++)
                {
                    result[i] = (short) readInt16(input);
                }

                return result;
            }

            case T_FLOAT:
            {
                Object[] result = new Object[size];

                for (int i = 0; i < size; i++)
                {
                    result[i] = readFloat32(input);
                }

                return result;
            }

            case T_DOUBLE:
            {
                Object[] result = new Object[size];

                for (int i = 0; i < size; i++)
                {
                    result[i] = (float) readFloat64(input);
                }

                return result;
            }

            case T_STRING:
            {
                String[] result = new String[size];

                for (int i = 0; i < size; i++)
                {
                    result[i] = readString(input);
                }

                return result;
            }

            case T_COMPRESSED_INT:
            {
                int[] result = new int[size];

                for (int i = 0; i < size; i++)
                {
                    result[i] = (int) readZigZagVarint(input);
                }

                return result;
            }

            case T_COMPRESSED_LONG:
            {
                long[] result = new long[size];

                for (int i = 0; i < size; i++)
                {
                    result[i] = readZigZagVarint(input);
                }

                return result;
            }

            case T_DICTIONARY:
            case T_HASHTABLE:
            {
                Object[] result = new Object[size];

                for (int i = 0; i < size; i++)
                {
                    result[i] = readDictionary(input);
                }

                return result;
            }

            case T_CUSTOM:
            {
                // 共享一个 customTypeID：读一次，然后 N 个 (长度 + 数据)。
                readByte(input);
                Object[] result = new Object[size];

                for (int i = 0; i < size; i++)
                {
                    int elementSize = (int) readVarint(input);

                    if (elementSize < 0 || elementSize > MAX_ARRAY_SIZE || elementSize > input.remaining())
                    {
                        return null;
                    }

                    byte[] data = new byte[elementSize];
                    input.get(data);
                    result[i] = data;
                }

                return result;
            }

            default:
            {
                Object[] result = new Object[size];

                for (int i = 0; i < size && input.hasRemaining(); i++)
                {
                    result[i] = deserialize(input, elementType);
                }

                return result;
            }
        }
    }

    private static HashMap<Object, Object> readOperationRequestInner(ByteBuffer input)
    {
        int operationCode = readByte(input);
        HashMap<Object, Object> result = new HashMap<>();
        result.put("operationCode", operationCode);
        result.put("parameters", readParameterTable(input));
        return result;
    }

    private static HashMap<Object, Object> readOperationResponseInner(ByteBuffer input)
    {
        int operationCode = readByte(input);
        int returnCode = readInt16(input);
        String debugMessage = "";

        if (input.hasRemaining())
        {
            Object debug = deserialize(input, readByte(input));

            if (debug instanceof String)
            {
                debugMessage = (String) debug;
            }
        }

        HashMap<Object, Object> result = new HashMap<>();
        result.put("operationCode", operationCode);
        result.put("returnCode", returnCode);
        result.put("debugMessage", debugMessage);
        result.put("parameters", readParameterTable(input));
        return result;
    }

    private static HashMap<Object, Object> readEventDataInner(ByteBuffer input)
    {
        int code = readByte(input);
        HashMap<Object, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("parameters", readParameterTable(input));
        return result;
    }

    // ---------------- 基础读取（小端 / LEB128 / zigzag） ----------------

    private static void delimit(ByteBuffer input, int required, String what)
    {
        if (input.remaining() < required)
        {
            throw new IllegalArgumentException(what + " truncated: need " + required
                    + " bytes, only " + input.remaining() + " left");
        }
    }

    private static int readByte(ByteBuffer input)
    {
        if (!input.hasRemaining())
        {
            throw new IllegalArgumentException("Protocol18 truncated: expected byte");
        }

        return input.get() & 0xFF;
    }

    private static int readInt16(ByteBuffer input)
    {
        if (input.remaining() < 2)
        {
            throw new IllegalArgumentException("Protocol18 truncated: expected int16");
        }

        return (short) ((input.get() & 0xFF) | ((input.get() & 0xFF) << 8));
    }

    private static int readUInt16(ByteBuffer input)
    {
        if (input.remaining() < 2)
        {
            throw new IllegalArgumentException("Protocol18 truncated: expected uint16");
        }

        return (input.get() & 0xFF) | ((input.get() & 0xFF) << 8);
    }

    private static float readFloat32(ByteBuffer input)
    {
        if (input.remaining() < 4)
        {
            throw new IllegalArgumentException("Protocol18 truncated: expected float32");
        }

        int bits = (input.get() & 0xFF)
                | ((input.get() & 0xFF) << 8)
                | ((input.get() & 0xFF) << 16)
                | ((input.get() & 0xFF) << 24);
        return Float.intBitsToFloat(bits);
    }

    private static double readFloat64(ByteBuffer input)
    {
        if (input.remaining() < 8)
        {
            throw new IllegalArgumentException("Protocol18 truncated: expected float64");
        }

        long bits = 0;

        for (int i = 0; i < 8; i++)
        {
            bits |= (long) (input.get() & 0xFF) << (8 * i);
        }

        return Double.longBitsToDouble(bits);
    }

    private static String readString(ByteBuffer input)
    {
        int length = (int) readVarint(input);

        if (length <= 0 || length > input.remaining())
        {
            return "";
        }

        byte[] bytes = new byte[length];
        input.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 7 位一组的无符号 LEB128（PC readCompressedUint32）。 */
    private static long readVarint(ByteBuffer input)
    {
        long result = 0;
        int shift = 0;

        for (int i = 0; i < 10; i++)
        {
            int b = readByte(input);
            result |= (long) (b & 0x7F) << shift;

            if ((b & 0x80) == 0)
            {
                return result;
            }

            shift += 7;
        }

        throw new IllegalArgumentException("Protocol18 varint too long");
    }

    private static long readZigZagVarint(ByteBuffer input)
    {
        long raw = readVarint(input);
        return (raw >>> 1) ^ -(raw & 1L);
    }

    private static boolean isComparableKey(Object key)
    {
        return key == null
                || key instanceof String
                || key instanceof Integer
                || key instanceof Long
                || key instanceof Short
                || key instanceof Byte
                || key instanceof Boolean
                || key instanceof Float
                || key instanceof Double;
    }
}
