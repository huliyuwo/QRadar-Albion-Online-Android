package com.minhui.vpn.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * 最小 SOCKS5 客户端（RFC 1928）：无认证握手、TCP CONNECT、UDP ASSOCIATE。
 * 用于把 VPN 隧道的流量链式转交给本机上游代理（如内置 mihomo）：
 *  - TCP 直接作为透明流转发；
 *  - UDP 走 ASSOCIATE 中继，每包加 10 字节 SOCKS5 UDP 头。
 * 若节点不支持 UDP，中继无回包，调用方会自动回退直连。
 *
 * 基于非阻塞 SocketChannel：channel 已注册到 selector，不能切阻塞模式
 * （IllegalBlockingModeException），故握手用带截止时间的轮询读写完成；
 * 对端是本机 mihomo，实际耗时毫秒级，短暂占用 selector 线程可接受。
 */
public class Socks5Client
{
    private static final byte VERSION = 0x05;
    private static final byte AUTH_NONE = 0x00;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte CMD_UDP_ASSOCIATE = 0x03;
    private static final byte ATYP_IPV4 = 0x01;
    private static final byte ATYP_DOMAIN = 0x03;
    private static final byte ATYP_IPV6 = 0x04;

    private Socks5Client()
    {
    }

    /** 方法协商：仅声明"无认证"。 */
    public static void handshake(SocketChannel channel, long timeoutMs) throws IOException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        writeFully(channel, new byte[]{VERSION, 0x01, AUTH_NONE}, deadline);

        byte[] reply = readFully(channel, 2, deadline);

        if (reply[0] != VERSION || reply[1] != AUTH_NONE)
        {
            throw new IOException("SOCKS5 handshake rejected, version=" + reply[0] + ", method=" + reply[1]);
        }
    }

    /** 发起 TCP CONNECT，成功后 channel 即为到目标地址的透明流。 */
    public static void connect(SocketChannel channel, InetAddress address, int port, long timeoutMs) throws IOException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        sendRequest(channel, CMD_CONNECT, address, port, deadline);
        readReply(channel, deadline);
    }

    /**
     * 申请 UDP 中继（UDP ASSOCIATE），返回服务端给出的中继地址。
     * 注意：很多实现（含 mihomo）会返回 0.0.0.0:port，调用方需要把它换成代理主机地址。
     */
    public static InetSocketAddress udpAssociate(SocketChannel channel, long timeoutMs) throws IOException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        sendRequest(channel, CMD_UDP_ASSOCIATE, InetAddress.getByName("0.0.0.0"), 0, deadline);
        return readReply(channel, deadline);
    }

    /** 把 UDP payload 封装成 SOCKS5 UDP 请求帧：RSV(2) FRAG(1) ATYP(1) ADDR(n) PORT(2) DATA。 */
    public static byte[] wrapUdpFrame(InetAddress destination, int destinationPort, ByteBuffer payload)
    {
        byte[] address = destination.getAddress();
        int payloadLength = payload.remaining();
        byte[] frame = new byte[4 + address.length + 2 + payloadLength];

        int index = 0;
        frame[index++] = 0x00;   // RSV
        frame[index++] = 0x00;   // RSV
        frame[index++] = 0x00;   // FRAG
        frame[index++] = atypFor(address);
        System.arraycopy(address, 0, frame, index, address.length);
        index += address.length;
        frame[index++] = (byte) ((destinationPort >> 8) & 0xFF);
        frame[index++] = (byte) (destinationPort & 0xFF);
        payload.get(frame, index, payloadLength);

        return frame;
    }

    /** 解析 SOCKS5 UDP 帧头，返回 payload 在帧内的起始偏移（ATYP 自适应）。 */
    public static int udpFramePayloadOffset(byte[] frame, int frameLength) throws IOException
    {
        if (frameLength < 10)
        {
            throw new IOException("SOCKS5 UDP datagram too short: " + frameLength);
        }

        int atyp = frame[3] & 0xFF;

        switch (atyp)
        {
            case ATYP_IPV4:
                return 10;

            case ATYP_IPV6:
                return 22;

            case ATYP_DOMAIN:
                int domainLength = frame[4] & 0xFF;

                if (frameLength < 5 + domainLength + 2)
                {
                    throw new IOException("SOCKS5 UDP domain header truncated");
                }

                return 4 + 1 + domainLength + 2;

            default:
                throw new IOException("Unknown SOCKS5 ATYP in UDP frame: " + atyp);
        }
    }

    private static byte atypFor(byte[] address)
    {
        return address.length == 4 ? ATYP_IPV4 : ATYP_IPV6;
    }

    private static void sendRequest(SocketChannel channel, byte command, InetAddress address, int port, long deadline) throws IOException
    {
        byte[] addr = address.getAddress();
        ByteBuffer request = ByteBuffer.allocate(4 + addr.length + 2);
        request.put(VERSION);
        request.put(command);
        request.put((byte) 0x00);   // RSV
        request.put(atypFor(addr));
        request.put(addr);
        request.putShort((short) port);

        writeFully(channel, request.array(), deadline);
    }

    private static InetSocketAddress readReply(SocketChannel channel, long deadline) throws IOException
    {
        byte[] header = readFully(channel, 4, deadline);

        if (header[0] != VERSION)
        {
            throw new IOException("Bad SOCKS5 reply version: " + header[0]);
        }

        if (header[1] != 0x00)
        {
            throw new IOException("SOCKS5 request failed, reply code: " + header[1]);
        }

        int atyp = header[3] & 0xFF;
        InetAddress boundAddress;

        switch (atyp)
        {
            case 0x01:
                boundAddress = InetAddress.getByAddress(readFully(channel, 4, deadline));
                break;
            case 0x04:
                boundAddress = InetAddress.getByAddress(readFully(channel, 16, deadline));
                break;
            case 0x03:
                int length = readFully(channel, 1, deadline)[0] & 0xFF;
                boundAddress = InetAddress.getByName(new String(readFully(channel, length, deadline), "ISO-8859-1"));
                break;
            default:
                throw new IOException("Unknown SOCKS5 ATYP in reply: " + atyp);
        }

        byte[] portBytes = readFully(channel, 2, deadline);
        int boundPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);
        return new InetSocketAddress(boundAddress, boundPort);
    }

    private static void writeFully(SocketChannel channel, byte[] data, long deadline) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        while (buffer.hasRemaining())
        {
            if (channel.write(buffer) == 0)
            {
                checkDeadline(deadline);
            }
        }
    }

    private static byte[] readFully(SocketChannel channel, int length, long deadline) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate(length);

        while (buffer.hasRemaining())
        {
            int read = channel.read(buffer);

            if (read < 0)
            {
                throw new IOException("SOCKS5 connection closed by peer");
            }

            if (read == 0)
            {
                checkDeadline(deadline);
            }
        }

        return buffer.array();
    }

    private static void checkDeadline(long deadline) throws IOException
    {
        if (System.currentTimeMillis() > deadline)
        {
            throw new IOException("SOCKS5 handshake timed out");
        }

        try
        {
            Thread.sleep(1);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("SOCKS5 handshake interrupted");
        }
    }
}
