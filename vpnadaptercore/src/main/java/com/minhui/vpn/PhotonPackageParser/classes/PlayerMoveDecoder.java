package com.minhui.vpn.PhotonPackageParser.classes;

import android.util.Log;

/**
 * 玩家坐标解码器（Photon Move 事件第二层的 8 字节 XorCode 异或）。
 *
 * <p>背景：Albion 的 Move 事件（event code 3）里，怪物/资源坐标是明文 float，
 * 而<b>玩家</b>坐标额外用一段 8 字节 XorCode 异或（PC 版 OpenRadar 的
 * {@code internal/photon/events.go} 以及 docs/technical/PLAYER_POSITIONS_MITM.md 都记录了这一层），
 * 所以旧代码直接 {@code Float.intBitsToFloat(bytes[9..13])} 会解出 NaN/Inf 并把整条事件丢弃 ——
 * 这就是“雷达上永远看不到其他玩家”的根本原因。
 *
 * <p>本类做“自恢复”，不需要 MITM / AES 密钥：本地玩家的真实坐标是已知的
 * （客户端 Move 请求里是明文，由 PhotonPacketParser.onRequest 持续更新），
 * 当本地玩家<b>静止</b>时，服务器回显的自身 Move 事件坐标必然等于当前坐标，于是
 * {@code xorKey[j] = cipher[9+j] XOR plaintext[j]} 直接得到 8 字节解码表
 * （XOR 可逆；坐标区正好 8 字节 = 一个完整周期，所以不存在对齐歧义）。
 *
 * <p>拿到解码表后 {@link #decode(byte[], int)} 就能解出所有玩家坐标。
 * 若始终没有自身 Move 事件（例如服务器不回显自己的移动），玩家仍会被识别/计数
 * （见 PlayersHandler.NewCharacter），只是无法放到地图上 —— 与 PC 版行为一致。
 */
public final class PlayerMoveDecoder
{
    private static final String TAG = "RadarDiag";

    /** 单张地图世界坐标合理范围。 */
    private static final float WORLD_LIMIT = 5000f;

    /** 判定“本地玩家静止”的时间窗口：自身坐标这么久没变化才用来反推 XorCode。 */
    private static final long STATIONARY_WINDOW_MS = 200L;

    /** 用解码表校验自身坐标时允许的误差（服务器回显与客户端请求之间的延迟）。 */
    private static final float VERIFY_TOLERANCE = 120f;

    /** 8 字节解码表（相对坐标区起点）。 */
    private static volatile byte[] xorKey;

    private static int diagSelfLogCount;
    private static int diagPlayerLogCount;

    private PlayerMoveDecoder()
    {
    }

    public static boolean isReady()
    {
        return xorKey != null;
    }

    /**
     * 处理一条“自身”的 Move 事件。
     *
     * @param bytes   Move 事件参数 1 的原始字节
     * @param offset  坐标起始下标（PC 版为 9）
     * @param knownX  本地玩家当前坐标 X（来自客户端明文 Move 请求）
     * @param knownY  本地玩家当前坐标 Y
     * @param posAgeMs 本地玩家坐标距上次变化的毫秒数
     */
    public static void onSelfMove(byte[] bytes, int offset, float knownX, float knownY, long posAgeMs)
    {
        if (bytes == null || bytes.length < offset + 8)
        {
            return;
        }

        if (xorKey != null)
        {
            float[] decoded = decode(bytes, offset);

            // 解码表失效自检：静止时解出来的自身坐标必须接近已知本地坐标
            if (decoded != null && isPlausible(knownX, knownY) && posAgeMs >= STATIONARY_WINDOW_MS
                    && distance(decoded[0], decoded[1], knownX, knownY) > VERIFY_TOLERANCE)
            {
                Log.w(TAG, "XorKey stale (self decoded=" + decoded[0] + "," + decoded[1]
                        + " known=" + knownX + "," + knownY + "), re-deriving");
                xorKey = null;
                return;
            }

            if (diagSelfLogCount++ < 10)
            {
                Log.d(TAG, "SELF decoded=" + (decoded == null ? "null"
                        : decoded[0] + "," + decoded[1]) + " known=" + knownX + "," + knownY
                        + " age=" + posAgeMs);
            }

            return;
        }

        if (diagSelfLogCount++ < 10)
        {
            Log.d(TAG, "SELFMOVE raw=" + hex(bytes) + " known=" + knownX + "," + knownY
                    + " age=" + posAgeMs + " len=" + bytes.length);
        }

        // 只有静止时的自身事件才能当作“已知明文/密文对”
        if (posAgeMs < STATIONARY_WINDOW_MS || !isPlausible(knownX, knownY))
        {
            return;
        }

        xorKey = deriveKey(bytes, offset, knownX, knownY);

        Log.d(TAG, "XorCode derived key=" + hex(xorKey) + " from plain=" + knownX + "," + knownY);
    }

    /** xorKey[j] = cipher[offset+j] XOR plaintext[j]（raw float bits，小端）。 */
    private static byte[] deriveKey(byte[] bytes, int offset, float knownX, float knownY)
    {
        byte[] plainBits = bitsOf(knownX, knownY);
        byte[] key = new byte[8];

        for (int j = 0; j < 8; j++)
        {
            key[j] = (byte) (bytes[offset + j] ^ plainBits[j]);
        }

        return key;
    }

    /**
     * 解一条 Move 事件里的坐标（玩家）。解不出（未拿到解码表 / 不合理）返回 null。
     */
    public static float[] decode(byte[] bytes, int offset)
    {
        byte[] key = xorKey;

        if (key == null || bytes == null || bytes.length < offset + 8)
        {
            return null;
        }

        float[] decoded = decodeWith(bytes, offset, key);

        if (decoded != null && diagPlayerLogCount++ < 12)
        {
            Log.d(TAG, "player move decoded=" + decoded[0] + "," + decoded[1]);
        }

        return decoded;
    }

    private static float[] decodeWith(byte[] bytes, int offset, byte[] key)
    {
        int rawX = 0;
        int rawY = 0;

        for (int i = 0; i < 4; i++)
        {
            rawX |= ((bytes[offset + i] ^ key[i]) & 0xFF) << (8 * i);
            rawY |= ((bytes[offset + 4 + i] ^ key[4 + i]) & 0xFF) << (8 * i);
        }

        float x = Float.intBitsToFloat(rawX);
        float y = Float.intBitsToFloat(rawY);

        return isPlausible(x, y) ? new float[]{x, y} : null;
    }

    private static byte[] bitsOf(float x, float y)
    {
        int xBits = Float.floatToRawIntBits(x);
        int yBits = Float.floatToRawIntBits(y);
        byte[] bits = new byte[8];

        for (int i = 0; i < 4; i++)
        {
            bits[i] = (byte) ((xBits >>> (8 * i)) & 0xFF);
            bits[4 + i] = (byte) ((yBits >>> (8 * i)) & 0xFF);
        }

        return bits;
    }

    /** 有限、非零、落在单张地图范围内。 */
    public static boolean isPlausible(float x, float y)
    {
        return Float.isFinite(x) && Float.isFinite(y)
                && Math.abs(x) < WORLD_LIMIT && Math.abs(y) < WORLD_LIMIT
                && !(x == 0f && y == 0f);
    }

    private static float distance(float x1, float y1, float x2, float y2)
    {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static String hex(byte[] data)
    {
        if (data == null)
        {
            return "null";
        }

        StringBuilder builder = new StringBuilder(data.length * 2);

        for (byte value : data)
        {
            builder.append(String.format("%02x", value));
        }

        return builder.toString();
    }
}
