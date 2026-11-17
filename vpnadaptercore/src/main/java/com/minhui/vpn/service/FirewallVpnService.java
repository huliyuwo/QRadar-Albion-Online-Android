package com.minhui.vpn.service;

import static com.minhui.vpn.VPNConstants.VPN_SP_NAME;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import androidx.annotation.Keep;

import com.minhui.vpn.Packet;
import com.minhui.vpn.ProxyConfig;
import com.minhui.vpn.R;
import com.minhui.vpn.UDPServer;
import com.minhui.vpn.http.HttpRequestHeaderParser;
import com.minhui.vpn.nat.NatSession;
import com.minhui.vpn.nat.NatSessionManager;
import com.minhui.vpn.processparse.PortHostService;
import com.minhui.vpn.proxy.TcpProxyServer;
import com.minhui.vpn.proxy.UpstreamProxyConfig;
import com.minhui.vpn.tcpip.IPHeader;
import com.minhui.vpn.tcpip.TCPHeader;
import com.minhui.vpn.tcpip.UDPHeader;
import com.minhui.vpn.tunnel.TcpTunnel;
import com.minhui.vpn.utils.AppDebug;
import com.minhui.vpn.utils.CommonMethods;
import com.minhui.vpn.utils.DebugLog;
import com.minhui.vpn.utils.ThreadProxy;
import com.minhui.vpn.utils.TimeFormatUtil;
import com.minhui.vpn.utils.VpnServiceHelper;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;


/**
 * Created by zengzheying on 15/12/28.
 */
@Keep public class FirewallVpnService extends VpnService implements Runnable
{
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything
    // 国内公共 DNS：Google DNS 的 UDP 53 在国内被阻断，直连 UDP 解析必须用境内可达的 DNS
    private static final String DNS_FIRST = "223.5.5.5";
    private static final String DNS_SECOND = "119.29.29.29";

    private static int ID;
    private static int LOCAL_IP;
    // volatile：UI 线程停止 VPN 时改写，工作线程循环里读取，必须保证可见性，
    // 否则 JIT 可能把旧值缓存在寄存器里，循环永远看不到"已停止"，系统 VPN 一直挂着
    private volatile boolean IsRunning = false;
    private Thread mVPNThread;
    private ParcelFileDescriptor mVPNInterface;
    private TcpProxyServer mTcpProxyServer;
    private FileOutputStream mVPNOutputStream;

    private final Object tunWriteLock = new Object();
    private volatile boolean udpWriterRunning = false;
    private Thread udpWriterThread;

    private byte[] mPacket;
    private IPHeader mIPHeader;
    private TCPHeader mTCPHeader;
    private Handler mHandler;
    private LinkedBlockingQueue<Packet> udpQueue;
    private FileInputStream in;
    private UDPServer udpServer;
    private final String selectPackage =  "com.albiononline";
    private final UDPHeader mUDPHeader;
    private final ByteBuffer mDNSBuffer;
    public static final int MUTE_SIZE = 2048;

    public static long vpnStartTime;
    public static String lastVpnStartTimeFormat = null;
    private SharedPreferences sp;

    public FirewallVpnService()
    {
        ID++;
        mHandler = new Handler();
        mPacket = new byte[MUTE_SIZE];
        mIPHeader = new IPHeader(mPacket, 0);

        //Offset = ip报文头部长度
        mTCPHeader = new TCPHeader(mPacket, 20);
        mUDPHeader = new UDPHeader(mPacket, 20);

        //Offset = ip报文头部长度 + udp报文头部长度 = 28
        mDNSBuffer = ((ByteBuffer) ByteBuffer.wrap(mPacket).position(28)).slice();

        DebugLog.i("New VPNService(%d)\n", ID);
    }

    private static volatile FirewallVpnService currentInstance;

    /** 代理开关切换时调用：断开现有 TCP/UDP 会话，让新流量按新出口重建。雷达抓包线程不受影响。 */
    public static void closeAllConnections()
    {
        FirewallVpnService service = currentInstance;

        if (service != null && service.udpServer != null)
        {
            service.udpServer.closeAllUDPConn();
        }

        TcpTunnel.closeAll();
        // 每次切换重新开始：之前判定"只能直连"的主机允许再试一次代理
        UpstreamProxyConfig.clearRuntimeDirectHosts();
        DebugLog.i("closeAllConnections: switch route for new flows\n");
    }

    //启动Vpn工作线程
    @Override
    public void onCreate()
    {
        DebugLog.i("VPNService(%s) created.\n", ID);
        currentInstance = this;
        sp = getSharedPreferences(VPN_SP_NAME, Context.MODE_PRIVATE);
        UpstreamProxyConfig.load(this);
        VpnServiceHelper.onVpnServiceCreated(this);
        mVPNThread = new Thread(this, "VPNServiceThread");
        mVPNThread.start();
        setVpnRunningStatus(true);

        //   notifyStatus(new VPNEvent(VPNEvent.Status.STARTING));
        super.onCreate();
    }

    //只设置IsRunning = true;
    @Override public int onStartCommand(Intent intent, int flags, int startId)
    {
        return super.onStartCommand(intent, flags, startId);
    }

    //停止Vpn工作线程
    @Override public void onDestroy()
    {
        Log.d("VPNService","stopped");
        currentInstance = null;
        DebugLog.i("VPNService(%s) destroyed.\n", ID);

        if (mVPNThread != null)
        {
            IsRunning = false;
            wakeupTunRead();
            mVPNThread.interrupt();
        }

        VpnServiceHelper.onVpnServiceDestroy();
        super.onDestroy();
    }

    //建立VPN，同时监听出口流量
    private void runVPN() throws Exception
    {
        DebugLog.i("DIAG runVPN enter\n");
        this.mVPNInterface = establishVPN();
        DebugLog.i("DIAG establishVPN done, iface=%s\n", mVPNInterface != null);
        startStream();
    }

    private void startStream() throws Exception
    {
        int size = 0;

        mVPNOutputStream = new FileOutputStream(mVPNInterface.getFileDescriptor());
        in = new FileInputStream(mVPNInterface.getFileDescriptor());

        // UDP 回包交给独立线程写 tun。主循环不再"每处理一个包睡 10ms"顺带轮询 udpQueue：
        // 那个 sleep 把整条隧道限死在约 100 包/秒（实测 ~70KB/s、TLS 握手多付 1~2 个 RTT），
        // 游戏单次 HTTP 请求的 3s 超时就是被它拖出来的。
        startUdpWriter();

        // tun 的 fd 是非阻塞的：没包时 read 返回 0 而不是挂起，直接循环会 100% 空转。
        // 用 poll 等可读：有包立刻返回，没包最多等 500ms（顺带周期性复查 IsRunning）。
        StructPollfd pollFd = new StructPollfd();
        pollFd.fd = mVPNInterface.getFileDescriptor();
        pollFd.events = (short) OsConstants.POLLIN;
        StructPollfd[] pollFds = new StructPollfd[]{pollFd};
        boolean pollSupported = true;

        while (size != -1 && IsRunning)
        {
            if (pollSupported)
            {
                try
                {
                    pollFd.revents = 0;
                    Os.poll(pollFds, 500);

                    if ((pollFd.revents & OsConstants.POLLIN) == 0)
                    {
                        continue;
                    }
                }
                catch (ErrnoException e)
                {
                    // EINTR 之类的临时错误，下一轮重试即可
                    continue;
                }
                catch (Throwable t)
                {
                    // 老系统上 Os.poll 不可用时退回"读一次让出 1ms"的轮询模式
                    pollSupported = false;
                    DebugLog.i("Os.poll unavailable: %s\n", t.toString());
                    continue;
                }
            }
            else
            {
                // 退化轮询：1ms 一次的节流，避免烧 CPU
                LockSupport.parkNanos(1_000_000L);
            }

            size = in.read(mPacket);

            if (size > 0)
            {
                if (mTcpProxyServer.Stopped)
                {
                    in.close();
                    throw new Exception("LocalServer stopped.");
                }

                onIPPacketReceived(mIPHeader, size);
            }
        }

        in.close();
        DebugLog.i("DIAG startStream exit: size=%d IsRunning=%s\n", size, IsRunning);
        disconnectVPN();
    }

    /** 专用线程把 UDPServer 的回包写回 tun，避免主循环为了轮询队列而空转/限速。 */
    private void startUdpWriter()
    {
        // VPN 重建时可能留下上一轮的写线程，先让它退出，避免两个线程同时往 tun 写
        udpWriterRunning = false;

        if (udpWriterThread != null)
        {
            udpWriterThread.interrupt();
            udpWriterThread = null;
        }

        udpWriterRunning = true;

        Thread writer = new Thread(() ->
        {
            while (udpWriterRunning && IsRunning)
            {
                Packet packet;

                try
                {
                    // 阻塞取队列（不轮询，空闲时 CPU 为零）
                    packet = udpQueue.poll(1, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (packet == null)
                {
                    continue;
                }

                try
                {
                    ByteBuffer buffer = packet.backingBuffer;
                    buffer.flip();

                    // 只写 limit 字节：之前写的是整个 array（2560B 缓冲），
                    // 会在 tun 里多带一截垃圾数据
                    writeToTun(buffer.array(), 0, buffer.limit());
                }
                catch (Exception e)
                {
                    // 隧道正在关闭时的写入失败属正常情况
                }
            }
        }, "TunUdpWriter");

        writer.setDaemon(true);
        writer.start();
        udpWriterThread = writer;
    }

    /** TCP 转发线程与 UDP 写回线程共用同一个 tun 输出流，必须串行写。 */
    private void writeToTun(byte[] data, int offset, int length) throws IOException
    {
        synchronized (tunWriteLock)
        {
            if (mVPNOutputStream == null)
            {
                throw new IOException("tun output stream is closed");
            }

            mVPNOutputStream.write(data, offset, length);
        }
    }

    boolean onIPPacketReceived(IPHeader ipHeader, int size) throws IOException
    {
        boolean hasWrite = false;

        switch (ipHeader.getProtocol())
        {
            case IPHeader.TCP:
                hasWrite = onTcpPacketReceived(ipHeader, size);

                break;
            case IPHeader.UDP:
                onUdpPacketReceived(ipHeader, size);
                break;

            default:
                break;
        }
        return hasWrite;
    }

    private void onUdpPacketReceived(IPHeader ipHeader, int size) throws UnknownHostException
    {
        TCPHeader tcpHeader = mTCPHeader;
        short portKey = tcpHeader.getSourcePort();

        NatSession session = NatSessionManager.getSession(portKey);

        if (session == null || session.remoteIP != ipHeader.getDestinationIP() || session.remotePort != tcpHeader.getDestinationPort())
        {
            session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort(), NatSession.UDP);
            session.vpnStartTime = vpnStartTime;

            ThreadProxy.getInstance().execute(() ->
            {
                if(PortHostService.getInstance()!=null)
                {
                    PortHostService.getInstance().refreshSessionInfo();
                }
            });
        }

        // session.lastRefreshTime = System.currentTimeMillis();
        // session.packetSent++; //注意顺序

        byte[] bytes = Arrays.copyOf(mPacket, mPacket.length);

        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, 0, size);
        byteBuffer.limit(size);

        //Log.d("payload",""+bytes);

        Packet packet = new Packet(byteBuffer);
        udpServer.processUDPPacket(packet, portKey);
    }

    private boolean onTcpPacketReceived(IPHeader ipHeader, int size) throws IOException
    {
        boolean hasWrite = false;
        TCPHeader tcpHeader = mTCPHeader;
        tcpHeader.mOffset = ipHeader.getHeaderLength();

        if (tcpHeader.getSourcePort() == mTcpProxyServer.port)
        {
            //VPNLog.d(TAG, "process  tcp packet from net ");
            NatSession session = NatSessionManager.getSession(tcpHeader.getDestinationPort());
            Log.d("FirewallVpnService", "DIAG tcp from-net src=" + CommonMethods.ipIntToString(ipHeader.getSourceIP()) + ":" + tcpHeader.getSourcePort() + " dst=" + CommonMethods.ipIntToString(ipHeader.getDestinationIP()) + ":" + tcpHeader.getDestinationPort() + " session=" + (session != null) + " size=" + size + " " + tcpHeader.toString());

            if (session != null)
            {
                ipHeader.setSourceIP(ipHeader.getDestinationIP());
                tcpHeader.setSourcePort(session.remotePort);
                ipHeader.setDestinationIP(LOCAL_IP);

                CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                writeToTun(ipHeader.mData, ipHeader.mOffset, size);
                //mReceivedBytes += size;
            }
            else
            {
                DebugLog.i("NoSession: %s %s\n", ipHeader.toString(), tcpHeader.toString());
            }
        }
        else
        {
            //VPNLog.d(TAG, "process  tcp packet to net ");

            short portKey = tcpHeader.getSourcePort();
            NatSession session = NatSessionManager.getSession(portKey);

            if (session == null || session.remoteIP != ipHeader.getDestinationIP() || session.remotePort != tcpHeader.getDestinationPort())
            {
                session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort(), NatSession.TCP);
                session.vpnStartTime = vpnStartTime;

                ThreadProxy.getInstance().execute(() ->
                {
                    PortHostService instance = PortHostService.getInstance();

                    if (instance != null)
                    {
                        instance.refreshSessionInfo();
                    }
                });
            }

            session.lastRefreshTime = System.currentTimeMillis();
            session.packetSent++;

            int tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength();

            if (tcpDataSize == 0)
            {
                Log.d("FirewallVpnService", "DIAG tcp to-net src=" + CommonMethods.ipIntToString(ipHeader.getSourceIP()) + ":" + tcpHeader.getSourcePort() + " dst=" + CommonMethods.ipIntToString(ipHeader.getDestinationIP()) + ":" + tcpHeader.getDestinationPort() + " pktSent=" + session.packetSent);
            }

            if (session.packetSent == 2 && tcpDataSize == 0)
            {
                return false;
            }

            if (session.bytesSent == 0 && tcpDataSize > 10)
            {
                int dataOffset = tcpHeader.mOffset + tcpHeader.getHeaderLength();
                HttpRequestHeaderParser.parseHttpRequestHeader(session, tcpHeader.mData, dataOffset, tcpDataSize);

                DebugLog.i("Host: %s\n", session.remoteHost);
                DebugLog.i("Request: %s %s\n", session.method, session.requestUrl);
            }
            else if (session.bytesSent > 0 && !session.isHttpsSession && session.isHttp && session.remoteHost == null && session.requestUrl == null)
            {
                int dataOffset = tcpHeader.mOffset + tcpHeader.getHeaderLength();

                session.remoteHost = HttpRequestHeaderParser.getRemoteHost(tcpHeader.mData, dataOffset, tcpDataSize);
                session.requestUrl = "http://" + session.remoteHost + "/" + session.pathUrl;
            }

            ipHeader.setSourceIP(ipHeader.getDestinationIP());
            ipHeader.setDestinationIP(LOCAL_IP);
            tcpHeader.setDestinationPort(mTcpProxyServer.port);

            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
            writeToTun(ipHeader.mData, ipHeader.mOffset, size);

            session.bytesSent += tcpDataSize;
            //mSentBytes += size;
        }

        hasWrite = true;
        return hasWrite;
    }

    private void waitUntilPrepared()
    {
        while (prepare(this) != null)
        {
            try
            {
                Thread.sleep(10);
            }
            catch (InterruptedException e)
            {
                if (AppDebug.IS_DEBUG)
                {
                    e.printStackTrace();
                }

                DebugLog.e("waitUntilPrepared catch an exception %s\n", e);
            }
        }
    }
    private ParcelFileDescriptor establishVPN() throws Exception
    {
        Builder builder = new Builder();
        builder.setMtu(MUTE_SIZE);

        DebugLog.i("setMtu: %d\n", ProxyConfig.Instance.getMTU());

        ProxyConfig.IPAddress ipAddress = ProxyConfig.Instance.getDefaultLocalIP();
        LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);
        builder.addAddress(ipAddress.Address, ipAddress.PrefixLength);
        DebugLog.i("addAddress: %s/%d\n", ipAddress.Address, ipAddress.PrefixLength);

        builder.addRoute(VPN_ROUTE, 0);

        builder.addDnsServer(DNS_FIRST);
        builder.addDnsServer(DNS_SECOND);

        vpnStartTime = System.currentTimeMillis();
        lastVpnStartTimeFormat = TimeFormatUtil.formatYYMMDDHHMMSS(vpnStartTime);

        // 白名单=游戏+雷达自身。雷达必须在名单内：半 NAT 回环依赖内核代发的 SYN-ACK/ACK
        // 回到 tun，这些包按 socket 属主 UID 路由，雷达 UID 不在名单内会走物理网络断开回环。
        // mihomo 子进程与雷达同 UID，其连节点/拉订阅的出口流量也会被吸入隧道，
        // 由 UpstreamProxyConfig 的直连端点集合兜底（命中即直连转发，不再回塞 mihomo）。
        try
        {
            builder.addAllowedApplication(selectPackage);
            builder.addAllowedApplication(getPackageName());
        }
        catch (Exception e)
        {
            // 游戏未安装时不能退化成全流量捕获，直接放弃建立 VPN
            throw new Exception("target game not installed: " + selectPackage);
        }

        builder.setSession(getString(R.string.app_name));
        ParcelFileDescriptor pfdDescriptor = builder.establish();

        if (mTcpProxyServer != null)
        {
            mTcpProxyServer.stop();
        }

        mTcpProxyServer = new TcpProxyServer(0);
        mTcpProxyServer.start();

        //notifyStatus(new VPNEvent(VPNEvent.Status.ESTABLISHED));

        return pfdDescriptor;
    }

    @Override
    public void run()
    {
        try
        {
            DebugLog.i("VPNService(%s) work thread is Running...\n", ID);

            waitUntilPrepared();
            udpQueue = new LinkedBlockingQueue<>();

            //启动UDP服务（TCP代理服务在 establishVPN 内创建）
            udpServer = new UDPServer(this, udpQueue);
            udpServer.start();

            NatSessionManager.clearAllSession();

            if(PortHostService.getInstance()!=null)
            {
                PortHostService.startParse(getApplicationContext());
            }

            DebugLog.i("DnsProxy started.\n");

            ProxyConfig.Instance.onVpnStart(this);

            while (IsRunning)
            {
                runVPN();
            }

            DebugLog.i("DIAG run while exit, IsRunning=%s\n", IsRunning);
        }
        catch (InterruptedException e)
        {
            if (AppDebug.IS_DEBUG)
            {
                e.printStackTrace();
            }

            DebugLog.e("VpnService run catch an exception %s.\n", e);
        }
        catch (Exception e)
        {
            if (AppDebug.IS_DEBUG)
            {
                e.printStackTrace();
            }

            DebugLog.e("VpnService run catch an exception %s.\n", e);
        }
        finally
        {
            DebugLog.i("VpnService terminated");
            ProxyConfig.Instance.onVpnEnd(this);
            dispose();
        }
    }

    public void disconnectVPN()
    {
        try
        {
            if (mVPNInterface != null)
            {
                mVPNInterface.close();
                mVPNInterface = null;
            }
        }
        catch (Exception e)
        {
            //ignore
        }

        // notifyStatus(new VPNEvent(VPNEvent.Status.UNESTABLISHED));
        this.mVPNOutputStream = null;
    }

    private synchronized void dispose()
    {
        DebugLog.i("DIAG dispose enter, iface=%s\n", mVPNInterface != null);
        udpWriterRunning = false;

        if (udpWriterThread != null)
        {
            udpWriterThread.interrupt();
            udpWriterThread = null;
        }
        try
        {
            disconnectVPN();
            DebugLog.i("DIAG dispose disconnected\n");

            if (mTcpProxyServer != null)
            {
                mTcpProxyServer.stop();
                mTcpProxyServer = null;
                DebugLog.i("TcpProxyServer stopped.\n");
            }

            if(udpServer!=null)
            {
                udpServer.closeAllUDPConn();
            }

            ThreadProxy.getInstance().execute(() ->
            {
                if(PortHostService.getInstance() != null)
                {
                    PortHostService.getInstance().refreshSessionInfo();
                }

                PortHostService.stopParse(getApplicationContext());
            });

            stopSelf();
            setVpnRunningStatus(false);
        }
        catch (Exception e)
        {

        }
    }

    public boolean vpnRunningStatus() {
        return IsRunning;
    }

    public void setVpnRunningStatus(boolean isRunning) {
        boolean wasRunning = IsRunning;
        IsRunning = isRunning;
        DebugLog.i("DIAG setVpnRunningStatus %s -> %s, iface=%s\n", wasRunning, isRunning, mVPNInterface != null);

        // 停止时必须唤醒阻塞在 tun 读上的工作线程：
        // FileInputStream.read 阻塞期间不会检查 IsRunning，没有新包就永远退不出循环，
        // 结果就是 App 侧按钮已经变绿（认为已停止），系统却一直显示 VPN 已连接。
        if (wasRunning && !isRunning)
        {
            wakeupTunRead();
        }
    }

    /**
     * 向 tun 里送一个"唤醒包"：本进程 uid 在 VPN 白名单内，未保护 socket 发出的 UDP
     * 包会被路由进 tun，从而让阻塞中的 in.read(mPacket) 立即返回，工作线程得以复查
     * IsRunning 并退出。包本身由 UDPServer 当作普通 UDP 处理，无副作用。
     */
    private void wakeupTunRead()
    {
        if (mVPNInterface == null)
        {
            return;
        }

        new Thread(() ->
        {
            try
            {
                java.net.DatagramSocket socket = new java.net.DatagramSocket();
                byte[] probe = new byte[]{0};
                socket.send(new java.net.DatagramPacket(probe, probe.length,
                        java.net.InetAddress.getByName("1.1.1.1"), 9));
                socket.close();
                DebugLog.i("DIAG wakeup packet sent\n");
            }
            catch (Exception e)
            {
                // 唤醒失败不影响停止流程，接口关闭后 read 也会返回
                DebugLog.i("DIAG wakeup packet failed: %s\n", e.toString());
            }
        }, "TunWakeup").start();
    }
}
