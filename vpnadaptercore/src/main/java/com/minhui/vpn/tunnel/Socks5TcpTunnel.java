package com.minhui.vpn.tunnel;

import com.minhui.vpn.VPNLog;
import com.minhui.vpn.proxy.Socks5Client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * 外侧说 SOCKS5 的 TCP 隧道：socket 先连到上游代理端点（构造参数，见 TunnelFactory），
 * 连接建立后在非阻塞 channel 上完成 SOCKS5 握手与 CONNECT（目标为 mDestAddress），
 * 之后把流交还 RawTcpTunnel 的普通转发逻辑。
 */
public class Socks5TcpTunnel extends RawTcpTunnel
{
    private static final String TAG = Socks5TcpTunnel.class.getSimpleName();
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;

    public Socks5TcpTunnel(InetSocketAddress upstreamEndpoint, Selector selector, short portKey) throws IOException
    {
        super(upstreamEndpoint, selector, portKey);
    }

    @Override
    protected void onConnected() throws Exception
    {
        SocketChannel channel = innerChannel();

        try
        {
            // channel 已注册到 selector，不能切阻塞模式，握手用非阻塞轮询完成
            Socks5Client.handshake(channel, HANDSHAKE_TIMEOUT_MS);
            Socks5Client.connect(channel, mDestAddress.getAddress(), mDestAddress.getPort(), HANDSHAKE_TIMEOUT_MS);
        }
        catch (IOException e)
        {
            VPNLog.d(TAG, "SOCKS5 handshake failed for " + mDestAddress + ": " + e.getMessage());
            throw e;
        }

        super.onConnected();
    }
}
