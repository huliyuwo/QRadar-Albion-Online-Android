package com.minhui.vpn.tunnel;


import com.minhui.vpn.KeyHandler;
import com.minhui.vpn.VPNLog;
import com.minhui.vpn.nat.NatSession;
import com.minhui.vpn.nat.NatSessionManager;
import com.minhui.vpn.service.FirewallVpnService;
import com.minhui.vpn.utils.AppDebug;
import com.minhui.vpn.utils.DebugLog;
import com.minhui.vpn.utils.VpnServiceHelper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by zengzheying on 15/12/29.
 */
public abstract class TcpTunnel implements KeyHandler
{
    public static long sessionCount;

    /**
     * 远端 TCP 建连超时。之前没有超时：手机网络下 SYN 被丢/黑洞时，内核要等几十秒才失败，
     * 而客户端（游戏）这边的 TCP 早已被本地代理"半 NAT"应答为已连接，只能干等 ——
     * 表现就是"有时候卡在连接服务器"。超时后主动 dispose，客户端立刻收到 FIN/RST 并可重试。
     */
    private static final long CONNECT_TIMEOUT_MS = 6000;

    private static final ScheduledExecutorService CONNECT_TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable ->
            {
                Thread thread = new Thread(runnable, "TcpConnectTimeout");
                thread.setDaemon(true);
                return thread;
            });

    /** 所有存活隧道的登记表：切换代理开关时用来强制断开，让新出口立刻生效。 */
    private static final java.util.Set<TcpTunnel> ALL_TUNNELS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void closeAll()
    {
        for (TcpTunnel tunnel : ALL_TUNNELS)
        {
            try
            {
                tunnel.dispose();
            }
            catch (Exception ignored)
            {
            }
        }
    }

    /** 关闭所有发往该地址的隧道（一致性回退：UDP 判定必须直连时，TCP 也一起切直连）。 */
    public static int closeAllTo(InetAddress address)
    {
        if (address == null)
        {
            return 0;
        }

        int closed = 0;

        for (TcpTunnel tunnel : ALL_TUNNELS)
        {
            InetSocketAddress dest = tunnel.mDestAddress;

            if (dest != null && address.equals(dest.getAddress()))
            {
                try
                {
                    tunnel.dispose();
                    closed++;
                }
                catch (Exception ignored)
                {
                }
            }
        }

        return closed;
    }

    protected InetSocketAddress mDestAddress;
    private SocketChannel mInnerChannel;
    private Selector mSelector;
    private boolean isHttpsRequest = false;
    private TcpTunnel mBrotherTunnel;
    private final AtomicBoolean mDisposed = new AtomicBoolean();
    private InetSocketAddress mServerEP;
    short portKey;
    ConcurrentLinkedQueue<ByteBuffer> needWriteData = new ConcurrentLinkedQueue<>();

    /** 远端建连是否已完成（含 SOCKS5 握手），用于超时兜底判断。 */
    private volatile boolean mConnectEstablished;
    /** 建连开始时间，用于日志里的耗时统计。 */
    private volatile long mConnectStartTime;
    /** 建连超时兜底任务。 */
    private volatile ScheduledFuture<?> mConnectTimeoutFuture;

    public TcpTunnel(SocketChannel innerChannel, Selector selector)
    {
        mInnerChannel = innerChannel;
        mSelector = selector;
        sessionCount++;
        ALL_TUNNELS.add(this);
    }

    public TcpTunnel(InetSocketAddress serverAddress, Selector selector, short portKey) throws IOException
    {
        SocketChannel innerChannel = SocketChannel.open();
        innerChannel.configureBlocking(false);
        // 转发的是交互式小包（TLS 记录、控制消息），Nagle 只会叠加延迟
        innerChannel.socket().setTcpNoDelay(true);
        this.mInnerChannel = innerChannel;
        this.mSelector = selector;
        this.mServerEP = serverAddress;
        this.portKey = portKey;
        sessionCount++;
        ALL_TUNNELS.add(this);
    }

    protected SocketChannel innerChannel()
    {
        return mInnerChannel;
    }

    @Override
    public void onKeyReady(SelectionKey key)
    {
        // 先处理"连接完成"：建连期间如果同时被判可写，必须先走 finishConnect，
        // 否则 onConnected/onTunnelEstablished 不会被调用，隧道会一直挂起。
        if (key.isConnectable())
        {
            onConnectable();
        }
        else if (key.isReadable())
        {
            onReadable(key);
        }
        else if (key.isWritable())
        {
            onWritable(key);
        }
    }

    protected abstract void onConnected() throws Exception;

    protected abstract boolean isTunnelEstablished();

    protected abstract void beforeSend(ByteBuffer buffer) throws Exception;

    protected abstract void afterReceived(ByteBuffer buffer) throws Exception;

    protected abstract void onDispose();

    public void setBrotherTunnel(TcpTunnel brotherTunnel)
    {
        this.mBrotherTunnel = brotherTunnel;
    }

    public void connect(InetSocketAddress destAddress) throws Exception
    {
        if (VpnServiceHelper.protect(mInnerChannel.socket()))
        {
            mDestAddress = destAddress;
            mConnectStartTime = System.currentTimeMillis();
            mConnectEstablished = false;

            // 兜底超时：建连（含 SOCKS5 握手）超时就主动断开，让客户端尽快重试。
            mConnectTimeoutFuture = CONNECT_TIMEOUT_EXECUTOR.schedule(this::onConnectTimeout,
                    CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            mInnerChannel.register(mSelector, SelectionKey.OP_CONNECT, this);
            mInnerChannel.connect(mServerEP);

            DebugLog.i("Connecting to %s", mServerEP);
            TcpStats.recordAccepted(destAddress, viaProxy());
        }
        else
        {
            TcpStats.recordFailure(destAddress, 0, viaProxy(),
                    new IOException("VPN protect socket failed"));
            throw new Exception("VPN protect socket failed.");
        }
    }

    /** 建连超时兜底：主动断开隧道，客户端收到 FIN/RST 后可以立刻重试。 */
    private void onConnectTimeout()
    {
        if (mDisposed.get() || mConnectEstablished)
        {
            return;
        }

        long elapsed = System.currentTimeMillis() - mConnectStartTime;
        TcpStats.recordTimeout(mDestAddress, elapsed, viaProxy());
        VPNLog.w("VPNLog", "TCPCONN timeout target=" + mDestAddress + " server=" + mServerEP
                + " elapsed=" + elapsed + "ms -> close tunnel (fast-fail)");
        dispose();
    }

    private void cancelConnectTimeout()
    {
        ScheduledFuture<?> future = mConnectTimeoutFuture;

        if (future != null)
        {
            future.cancel(false);
            mConnectTimeoutFuture = null;
        }
    }

    /** 是否走上游 SOCKS5 代理（子类可覆盖）。 */
    protected boolean viaProxy()
    {
        return false;
    }

    public void onConnectable()
    {
        try
        {
            if (mInnerChannel.finishConnect())
            {
                onConnected();
                mConnectEstablished = true;
                cancelConnectTimeout();
                TcpStats.recordConnected(mDestAddress,
                        Math.max(0, System.currentTimeMillis() - mConnectStartTime), viaProxy());
                DebugLog.i("Connected to %s", mServerEP);
            }
            else
            {
                TcpStats.recordFailure(mDestAddress,
                        Math.max(0, System.currentTimeMillis() - mConnectStartTime), viaProxy(),
                        new IOException("finishConnect returned false"));
                DebugLog.e("Connect to %s failed.", mServerEP);
                this.dispose();
            }
        }
        catch (Exception e)
        {
            TcpStats.recordFailure(mDestAddress,
                    Math.max(0, System.currentTimeMillis() - mConnectStartTime), viaProxy(), e);
            if (AppDebug.IS_DEBUG)
            {
                e.printStackTrace(System.err);
            }

            DebugLog.e("Connect to %s failed: %s", mServerEP, e);
            this.dispose();
        }
    }

    protected void beginReceived() throws Exception
    {
        if (mInnerChannel.isBlocking())
        {
            mInnerChannel.configureBlocking(false);
        }

        mSelector.wakeup();
        mInnerChannel.register(mSelector, SelectionKey.OP_READ, this);
    }

    public void onReadable(SelectionKey key)
    {
        try
        {
            ByteBuffer buffer = ByteBuffer.allocate(FirewallVpnService.MUTE_SIZE);
            buffer.clear();
            int bytesRead = mInnerChannel.read(buffer);

            if (bytesRead > 0)
            {
                VPNLog.d("TcpTunnel", "DIAG read " + getClass().getSimpleName() + " bytes=" + bytesRead);
                buffer.flip();
                afterReceived(buffer);
                sendToBrother(key, buffer);
            }
            else if (bytesRead < 0)
            {
                this.dispose();
            }
        }
        catch (Exception ex)
        {
            if (AppDebug.IS_DEBUG)
            {
                ex.printStackTrace(System.err);
            }

            DebugLog.e("onReadable catch an exception: %s", ex);
            this.dispose();
        }
    }

    protected void sendToBrother(SelectionKey key, ByteBuffer buffer) throws Exception
    {
        if (isTunnelEstablished() && buffer.hasRemaining())
        {
            //    mBrotherTunnel.beforeSend(buffer);
            mBrotherTunnel.getWriteDataFromBrother(buffer);
        }
    }

    private void getWriteDataFromBrother(ByteBuffer buffer)
    {
        if (buffer.hasRemaining() && needWriteData.size() == 0)
        {
            int writeSize = 0;

            try
            {
                writeSize = write(buffer);
            }
            catch (Exception e)
            {
                writeSize = 0;
                e.printStackTrace();
            }

            if (writeSize > 0)
            {
                return;
            }
        }
        needWriteData.offer(buffer);

        try
        {
            mSelector.wakeup();

            // 建连未完成时必须保留 OP_CONNECT，否则 finishConnect/onConnected 永远等不到，
            // 隧道会"连上了却一直不通"。
            int ops = SelectionKey.OP_READ | SelectionKey.OP_WRITE;

            if (mInnerChannel.isConnectionPending())
            {
                ops |= SelectionKey.OP_CONNECT;
            }

            mInnerChannel.register(mSelector, ops, this);
        }
        catch (ClosedChannelException e)
        {
            e.printStackTrace();
        }
    }

    protected int write(ByteBuffer buffer) throws Exception
    {
        int byteSendSum = 0;
    
        while (buffer.hasRemaining())
        {
            int byteSent = mInnerChannel.write(buffer);
            byteSendSum += byteSent;

            if (byteSent == 0)
            {
                break;
            }
        }

        if (byteSendSum > 0)
        {
            VPNLog.d("TcpTunnel", "DIAG write " + getClass().getSimpleName() + " bytes=" + byteSendSum);
        }
        return byteSendSum;
    }


    public void onWritable(SelectionKey key)
    {
        try
        {
            ByteBuffer mSendRemainBuffer = needWriteData.poll();

            if (mSendRemainBuffer == null)
            {
                return;
            }

            write(mSendRemainBuffer);

            if (needWriteData.size() == 0)
            {
                try
                {
                    mSelector.wakeup();
                    mInnerChannel.register(mSelector, SelectionKey.OP_READ, this);
                }
                catch (ClosedChannelException e)
                {
                    e.printStackTrace();
                }
            }
        }
        catch (Exception ex)
        {
            if (AppDebug.IS_DEBUG)
            {
                ex.printStackTrace(System.err);
            }

            DebugLog.e("onWritable catch an exception: %s", ex);

            this.dispose();
        }
    }

    protected void onTunnelEstablished() throws Exception
    {
        this.beginReceived();
        mBrotherTunnel.beginReceived();
    }

    public void dispose()
    {
        disposeInternal(true);
    }

    void disposeInternal(boolean disposeBrother)
    {
        // CAS 保证并发 dispose（selector 线程 + 建连超时线程）只执行一次；
        // 不能用 synchronized 包住，否则两个互为 brother 的隧道同时 dispose 会死锁。
        if (!mDisposed.compareAndSet(false, true))
        {
            return;
        }

        cancelConnectTimeout();

        SocketChannel channel = mInnerChannel;

        if (channel != null)
        {
            try
            {
                channel.close();
            }
            catch (Exception ex)
            {
                if (AppDebug.IS_DEBUG)
                {
                    ex.printStackTrace(System.err);
                }

                DebugLog.e("InnerChannel close catch an exception: %s", ex);
            }
        }

        TcpTunnel brother = mBrotherTunnel;

        if (brother != null && disposeBrother)
        {
            brother.disposeInternal(false);
        }

        mInnerChannel = null;
        mSelector = null;
        mBrotherTunnel = null;
        sessionCount--;
        ALL_TUNNELS.remove(this);

        try
        {
            onDispose();
        }
        catch (Throwable ignored)
        {
        }

        NatSessionManager.removeSession(portKey);
    }

    public void setIsHttpsRequest(boolean isHttpsRequest)
    {
        this.isHttpsRequest = isHttpsRequest;
    }

    public boolean isHttpsRequest()
    {
        return isHttpsRequest;
    }
}
