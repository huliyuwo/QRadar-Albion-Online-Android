package com.minhui.vpn.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * 最小 SOCKS5 客户端（RFC 1928）：无认证握手、TCP CONNECT、UDP ASSOCIATE，
 * 以及 UDP 请求/数据报的帧封装与解帧工具。
 * 用于把 VPN 隧道的流量链式转交给本机上游代理（如 Termux 中不占 VpnService 的 mihomo）。
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
    public static void handshake(Socket socket) throws IOException
    {
        OutputStream out = socket.getOutputStream();
        out.write(new byte[]{VERSION, 0x01, AUTH_NONE});
        out.flush();

        byte[] reply = readFully(socket.getInputStream(), 2);

        if (reply[0] != VERSION || reply[1] != AUTH_NONE)
        {
            throw new IOException("SOCKS5 handshake rejected, version=" + reply[0] + ", method=" + reply[1]);
        }
    }

    /** 发起 TCP CONNECT，成功后 socket 即为到目标地址的透明流。 */
    public static void connect(Socket socket, InetAddress address, int port) throws IOException
    {
        sendRequest(socket, CMD_CONNECT, address, port);
        readReply(socket);
    }

    /** 发起 UDP ASSOCIATE，返回上游中继端点（BND.ADDR/BND.PORT）。 */
    public static InetSocketAddress udpAssociate(Socket socket) throws IOException
    {
        sendRequest(socket, CMD_UDP_ASSOCIATE, InetAddress.getByName("0.0.0.0"), 0);
        return readReply(socket);
    }

    /** 把一段 UDP payload 封装成 SOCKS5 UDP 请求帧（RSV/FRAG/ATYP/ADDR/PORT/DATA）。 */
    public static ByteBuffer wrapUdpFrame(InetAddress destination, int destinationPort, ByteBuffer payload)
    {
        byte[] address = destination.getAddress();
        ByteBuffer frame = ByteBuffer.allocate(4 + address.length + 2 + payload.remaining());
        frame.put((byte) 0x00);   // RSV
        frame.put((byte) 0x00);   // RSV
        frame.put((byte) 0x00);   // FRAG
        frame.put(atypFor(address));
        frame.put(address);
        frame.putShort((short) destinationPort);
        frame.put(payload);
        frame.flip();
        return frame;
    }

    /** 解析上游返回的 SOCKS5 UDP 数据报帧头长度，返回 payload 起始偏移。 */
    public static int udpFramePayloadOffset(byte[] frame, int frameLength) throws IOException
    {
        if (frameLength < 10)
        {
            throw new IOException("SOCKS5 UDP datagram too short: " + frameLength);
        }

        int atyp = frame[3] & 0xFF;
        int offset;

        switch (atyp)
        {
            case 0x01:  // IPv4: 4 字节头 + 4 地址 + 2 端口
                offset = 10;
                break;
            case 0x04:  // IPv6: 4 字节头 + 16 地址 + 2 端口
                offset = 22;
                break;
            case 0x03:  // DOMAIN: 4 字节头 + 1 长度 + N 域名 + 2 端口
                offset = 5 + (frame[4] & 0xFF) + 2;
                break;
            default:
                throw new IOException("Unknown SOCKS5 ATYP in datagram: " + atyp);
        }

        if (offset > frameLength)
        {
            throw new IOException("Malformed SOCKS5 UDP datagram, header " + offset + " > " + frameLength);
        }

        return offset;
    }

    private static byte atypFor(byte[] address)
    {
        return address.length == 4 ? ATYP_IPV4 : ATYP_IPV6;
    }

    private static void sendRequest(Socket socket, byte command, InetAddress address, int port) throws IOException
    {
        byte[] addr = address.getAddress();
        ByteBuffer request = ByteBuffer.allocate(4 + addr.length + 2);
        request.put(VERSION);
        request.put(command);
        request.put((byte) 0x00);   // RSV
        request.put(atypFor(addr));
        request.put(addr);
        request.putShort((short) port);

        OutputStream out = socket.getOutputStream();
        out.write(request.array());
        out.flush();
    }

    private static InetSocketAddress readReply(Socket socket) throws IOException
    {
        InputStream in = socket.getInputStream();
        byte[] header = readFully(in, 4);

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
                boundAddress = InetAddress.getByAddress(readFully(in, 4));
                break;
            case 0x04:
                boundAddress = InetAddress.getByAddress(readFully(in, 16));
                break;
            case 0x03:
                int length = in.read() & 0xFF;
                boundAddress = InetAddress.getByName(new String(readFully(in, length), "ISO-8859-1"));
                break;
            default:
                throw new IOException("Unknown SOCKS5 ATYP in reply: " + atyp);
        }

        byte[] portBytes = readFully(in, 2);
        int boundPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);
        return new InetSocketAddress(boundAddress, boundPort);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException
    {
        byte[] buffer = new byte[length];
        int offset = 0;

        while (offset < length)
        {
            int read = in.read(buffer, offset, length - offset);

            if (read < 0)
            {
                throw new IOException("SOCKS5 connection closed by peer");
            }

            offset += read;
        }

        return buffer;
    }
}
