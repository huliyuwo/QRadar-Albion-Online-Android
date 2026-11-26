package com.minhui.vpn.PhotonPackageParser.classes;

import android.util.Log;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Photon 加密链路（被动还原）。
 *
 * <p>Albion 的 UDP 5056 在登录后会切换到 Photon 加密：
 * <ul>
 *   <li>握手：双方交换 Diffie-Hellman 公钥（Oakley 768-bit 素数组，生成元 22）；</li>
 *   <li>密钥：{@code AES key = SHA256(共享密钥的字节)}，AES-256-CBC，IV 全 0；</li>
 *   <li>之后每条消息（Message）的负载整体被 AES 加密，解密后才能看到 op/event code 与参数表。</li>
 * </ul>
 *
 * <p>本类只做“被动还原”：公钥在握手包中是明文，所以不需要 MITM 也能算出共享密钥。
 * 尚未拿到握手样本前 {@link #isReady()} 一直为 false，解析器会走原来的明文路径。
 */
public final class PhotonCrypto
{
    private static final String TAG = "PhotonCrypto";

    /** RFC 2409 group 1（768-bit MODP）素数组，Photon 默认使用的 Oakley 组。 */
    private static final String PRIME_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"
                    + "29024E088A67CC74020BBEA63B139B22514A0879"
                    + "8E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
                    + "4FE1356D6D51C245E485B576625E7EC6F44C42E9"
                    + "A63A3620FFFFFFFFFFFFFFFF";

    /** Photon 默认生成元（文档记载为 22，若实测不符可改回 2）。 */
    private static final BigInteger GENERATOR = BigInteger.valueOf(22);

    private static final BigInteger PRIME = new BigInteger(PRIME_HEX, 16);

    /** 交换的公钥长度：768-bit = 96 字节，部分实现会补到 128 字节。 */
    private static final int[] KEY_LENGTHS = {96, 128};

    private static final PhotonCrypto INSTANCE = new PhotonCrypto();

    public static PhotonCrypto get()
    {
        return INSTANCE;
    }

    private byte[] localPrivate;
    private byte[] peerPublic;
    private byte[] aesKey;
    private volatile boolean ready;

    private PhotonCrypto()
    {
    }

    public boolean isReady()
    {
        return ready;
    }

    /**
     * 尝试把一段参数当成 DH 公钥吸收进来。
     * 第一次收到的是对端（服务器）公钥；第二次收到的是本机公钥时即可推导共享密钥。
     *
     * @return true 表示这次调用之后密钥已就绪
     */
    public boolean offerPublicKey(byte[] candidate)
    {
        if (candidate == null || !isKeyLength(candidate.length))
        {
            return false;
        }

        BigInteger peer = new BigInteger(1, candidate);

        // 公钥必须落在 (1, p-1) 内，否则一定是别的东西
        if (peer.compareTo(BigInteger.ONE) <= 0 || peer.compareTo(PRIME.subtract(BigInteger.ONE)) >= 0)
        {
            return false;
        }

        if (peerPublic == null)
        {
            peerPublic = candidate.clone();
            Log.d(TAG, "captured peer DH public key, len=" + candidate.length);
            return false;
        }

        if (Arrays.equals(peerPublic, candidate))
        {
            return false;
        }

        // 被动抓包到这里就走到头了：共享密钥 = peerPub^localPrivate mod p，
        // 而我们没有（也不可能有）任何一方的私钥。所以 MistEye 那种“能显示玩家”的实现
        // 只可能是 MITM（自己当 DH 对端）或运行时挂钩，而不是纯旁路解析。
        Log.w(TAG, "two DH public keys captured, but passive key derivation is impossible "
                + "(needs a private exponent / MITM)");
        return false;
    }

    /**
     * 由外部（MITM 会话或调试注入）提供 AES 密钥。
     * 长度必须 32 字节（AES-256）。
     */
    public boolean setAesKey(byte[] key)
    {
        if (key == null || key.length != 32)
        {
            Log.w(TAG, "setAesKey rejected, len=" + (key == null ? -1 : key.length));
            return false;
        }

        aesKey = key.clone();
        ready = true;
        Log.d(TAG, "AES key installed externally, ready=true");
        return true;
    }

    /** 解密一条 Photon 消息负载（AES-256-CBC / IV=0 / PKCS#7）。 */
    public byte[] decryptMessage(byte[] cipherText)
    {
        if (!ready || cipherText == null || cipherText.length == 0)
        {
            return cipherText;
        }

        try
        {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(new byte[16]));
            return cipher.doFinal(cipherText);
        }
        catch (Exception e)
        {
            Log.w(TAG, "decrypt failed len=" + cipherText.length + " : " + e);
            return cipherText;
        }
    }

    /** 玩家坐标的第二层：8 字节 XorCode 异或。 */
    public static void xorInPlace(byte[] data, int offset, byte[] xorCode)
    {
        if (data == null || xorCode == null || xorCode.length == 0)
        {
            return;
        }

        for (int i = offset; i < data.length; i++)
        {
            data[i] ^= xorCode[(i - offset) % xorCode.length];
        }
    }

    private static boolean isKeyLength(int length)
    {
        for (int expected : KEY_LENGTHS)
        {
            if (length == expected)
            {
                return true;
            }
        }

        return false;
    }
}
