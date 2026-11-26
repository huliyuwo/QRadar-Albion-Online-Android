package com.minhui.vpn.tunnel;

import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;

import com.minhui.vpn.KeyHandler;
import com.minhui.vpn.Packet;
import com.minhui.vpn.PhotonPackageParser.PhotonPacketThreading;
import com.minhui.vpn.UDPServer;
import com.minhui.vpn.VPNConstants;
import com.minhui.vpn.VPNLog;
import com.minhui.vpn.nat.NatSession;
import com.minhui.vpn.nat.NatSessionManager;
import com.minhui.vpn.processparse.PortHostService;
import com.minhui.vpn.proxy.Socks5Client;
import com.minhui.vpn.proxy.UpstreamProxyConfig;
import com.minhui.vpn.utils.SocketUtils;
import com.minhui.vpn.utils.TcpDataSaveHelper;
import com.minhui.vpn.utils.ThreadProxy;
import com.minhui.vpn.utils.TimeFormatUtil;
import com.minhui.vpn.utils.VpnServiceHelper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Created by minhui.zhu on 2017/7/11.
 * Copyright © 2017年 minhui.zhu. All rights reserved.
 */

public class UDPTunnel implements KeyHandler
{
    private static final String TAG = UDPTunnel.class.getSimpleName();
    private final VpnService vpnService;
    private final Selector selector;
    private final UDPServer vpnServer;
    private final Queue<Packet> outputQueue;
    private TcpDataSaveHelper helper;
    private Packet referencePacket;
    private SelectionKey selectionKey;

    private DatagramChannel channel;
    private final ConcurrentLinkedQueue<Packet> toNetWorkPackets = new ConcurrentLinkedQueue<>();
    private static final int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.UDP_HEADER_SIZE;
    private Short portKey;
    String ipAndPort;
    private final NatSession session;
    private final Handler handler;

    // SOCKS5 UDP 中继状态
    private SocketChannel udpControlChannel;
    private boolean udpOverProxy = false;
    private ByteBuffer proxyReadBuffer;
    private InetAddress originalDestination;
    private int originalDestinationPort;

    /** 客户端（tun 侧）地址与端口：DNS 本地应答要用它们构造回包。 */
    private final InetAddress clientAddress;
    private final int clientPort;

    /** DNS 劫持：游戏/系统可能去查被墙的 DNS（8.8.8.8、216.239.32.10 等），直连必然超时。 */
    private static final int DNS_PORT = 53;
    private static final String DNS_FALLBACK = "223.5.5.5";

    /**
     * DNS 上游无应答时的看门狗：先重发 1 次；仍无应答就本地回 SERVFAIL，
     * 让系统解析器立刻换服务器重试（而不是干等 5s 超时）。这也覆盖了
     * "VPN 刚起来、上游 DNS 链路还没就绪"的启动竞态，避免静默丢包。
     */
    private static final long DNS_RETRY_DELAY_MS = 1500;
    private static final int DNS_MAX_RETRY = 1;
    private static final ScheduledExecutorService DNS_WATCHDOG_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable ->
            {
                Thread thread = new Thread(runnable, "DnsWatchdog");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean closed;
    private volatile PendingDns pendingDns;
    private volatile ScheduledFuture<?> dnsWatchdogFuture;

    /** 最近一次待应答的 DNS 查询（用于重发/SERVFAIL 本地兜底）。 */
    private static final class PendingDns
    {
        final byte[] payload;
        final InetAddress serverAddress;
        final int serverPort;

        PendingDns(byte[] payload, InetAddress serverAddress, int serverPort)
        {
            this.payload = payload;
            this.serverAddress = serverAddress;
            this.serverPort = serverPort;
        }
    }

    public UDPTunnel(VpnService vpnService, Selector selector, UDPServer vpnServer, Packet packet, Queue<Packet> outputQueue, short portKey)
    {
        this.vpnService = vpnService;
        this.selector = selector;
        this.vpnServer = vpnServer;
        this.referencePacket = packet;
        this.ipAndPort = packet.getIpAndPort();
        this.outputQueue = outputQueue;
        this.portKey = portKey;
        this.session = NatSessionManager.getSession(portKey);
        this.handler = new Handler(Looper.getMainLooper());
        this.clientAddress = packet.ip4Header.sourceAddress;
        this.clientPort = packet.udpHeader.sourcePort;

        if (VpnServiceHelper.isUDPDataNeedSave())
        {
            String helperDir = new StringBuilder()
                    .append(VPNConstants.DATA_DIR)
                    .append(TimeFormatUtil.formatYYMMDDHHMMSS(session.vpnStartTime))
                    .append("/")
                    .append(session.getUniqueName())
                    .toString();

            helper = new TcpDataSaveHelper(helperDir);
        }
    }

    private void processKey(SelectionKey key)
    {
        if (key.isWritable())
        {
            processSend();
        }
        else if (key.isReadable())
        {
            processReceived();
        }

        updateInterests();
    }

    private void processReceived()
    {
        //VPNLog.d(TAG, "processReceived:" + ipAndPort);
        ByteBuffer receiveBuffer = SocketUtils.getByteBuffer();
        int readBytes = 0;

        try
        {
            if (udpOverProxy)
            {
                // SOCKS5 UDP 中继：读进来的帧带 10 字节左右的头，剥掉后再当正常 payload 处理
                if (proxyReadBuffer == null)
                {
                    proxyReadBuffer = ByteBuffer.allocate(4096);
                }

                proxyReadBuffer.clear();
                readBytes = channel.read(proxyReadBuffer);

                if (readBytes > 0)
                {
                    int offset = Socks5Client.udpFramePayloadOffset(proxyReadBuffer.array(), readBytes);
                    int payloadLength = readBytes - offset;

                    receiveBuffer.position(HEADER_SIZE);
                    receiveBuffer.put(proxyReadBuffer.array(), offset, payloadLength);
                    readBytes = payloadLength;
                }
            }
            else
            {
                // Leave space for the header
                receiveBuffer.position(HEADER_SIZE);
                readBytes = channel.read(receiveBuffer);
            }
        }
        catch (Exception e)
        {
            VPNLog.d(TAG, "failed to read udp datas " + e);
            return;
        }

        if (readBytes == -1)
        {
            VPNLog.d(TAG, "read  data error :" + ipAndPort);
        }
        else if (readBytes == 0)
        {
            VPNLog.d(TAG, "read no data :" + ipAndPort);
        }
        else
        {
            VPNLog.d(TAG, "DIAG udp recv bytes=" + readBytes + " " + ipAndPort);
            Packet newPacket = referencePacket.duplicated();

            // DNS 应答：诊断记录 + 兜底剥离 AAAA + 取消看门狗
            if (newPacket.udpHeader != null && newPacket.udpHeader.sourcePort == DNS_PORT)
            {
                readBytes = handleDnsResponse(receiveBuffer, readBytes);
            }

            try
            {
                if (newPacket.udpHeader.destinationPort == 5056 || newPacket.udpHeader.sourcePort == 5056)
                {
                    ByteBuffer buffer = deepCopy(receiveBuffer);
                    // 只截到真实报文的末尾：之前用整个 2560B 缓冲区去解析，
                    // 报文末尾之后的残留数据会被当成 Photon 字段，
                    // 直接解析出天文数字的长度字段（曾导致 4GB 数组 OOM 崩进程）
                    buffer.limit(HEADER_SIZE + readBytes);
                    buffer.position(HEADER_SIZE);

                    photonPacketThreading.receivePacketFromOtherThread(buffer);
                }
            }
            catch (Exception ignored)
            {

            }

            newPacket.updateUDPBuffer(receiveBuffer, readBytes);
            receiveBuffer.position(HEADER_SIZE + readBytes);

            outputQueue.offer(newPacket);
        }
    }

    /**
     * 处理 DNS 应答：记录 qtype/记录数诊断，剥离 AAAA（可选兜底），并结束看门狗。
     *
     * @return 处理后的 payload 长度（可能因剥离 AAAA 变短）
     */
    private int handleDnsResponse(ByteBuffer receiveBuffer, int readBytes)
    {
        try
        {
            pendingDns = null;
            cancelDnsWatchdog();

            byte[] payload = new byte[readBytes];

            if (receiveBuffer.hasArray())
            {
                System.arraycopy(receiveBuffer.array(), receiveBuffer.arrayOffset() + HEADER_SIZE,
                        payload, 0, readBytes);
            }
            else
            {
                ByteBuffer copy = receiveBuffer.duplicate();
                copy.position(HEADER_SIZE);
                copy.get(payload, 0, readBytes);
            }

            DnsMessage.ResponseSummary summary = DnsMessage.describeResponse(payload, 0, payload.length);

            if (summary != null)
            {
                DnsStats.recordResponseDetail(summary.toString());
            }

            byte[] stripped = DnsMessage.stripAaaaRecords(payload, 0, payload.length);

            if (stripped != null && stripped.length <= receiveBuffer.capacity() - HEADER_SIZE)
            {
                receiveBuffer.position(HEADER_SIZE);
                receiveBuffer.put(stripped, 0, stripped.length);
                DnsStats.recordAaaaStripped(summary == null ? 1 : Math.max(1, summary.aaaaRecords),
                        summary == null ? null : summary.name);
                return stripped.length;
            }
        }
        catch (Throwable e)
        {
            VPNLog.w(TAG, "handleDnsResponse failed, forward raw reply: " + e);
        }

        return readBytes;
    }

    public static ByteBuffer deepCopy( ByteBuffer orig )
    {
        int pos = orig.position(), lim = orig.limit();

        try
        {
            orig.position(0).limit(orig.capacity()); // set range to entire buffer
            ByteBuffer toReturn = deepCopyVisible(orig); // deep copy range
            toReturn.position(pos).limit(lim); // set to original
            return toReturn;
        }
        finally // do in finally in case something goes wrong we don't bork the orig
        {
            orig.position(pos).limit(lim); // restore original
        }
    }

    public static ByteBuffer deepCopyVisible( ByteBuffer orig )
    {
        int pos = orig.position();

        try
        {
            ByteBuffer toReturn;

            // try to maintain implementation to keep performance
            if( orig.isDirect())
                toReturn = ByteBuffer.allocateDirect(orig.remaining());
            else
                toReturn = ByteBuffer.allocate(orig.remaining());

            toReturn.put(orig);
            toReturn.order(orig.order());

            return (ByteBuffer) toReturn.position(0);
        }
        finally
        {
            orig.position(pos);
        }
    }
    PhotonPacketThreading photonPacketThreading = new PhotonPacketThreading();

    private void processSend()
    {
        VPNLog.d(TAG, "DIAG processSend entered " + ipAndPort);
        Packet toNetWorkPacket = getToNetWorkPackets();

        if (toNetWorkPacket == null)
        {
            VPNLog.d(TAG, "write data  no packet ");
            return;
        }

        try
        {
            ByteBuffer payloadBuffer = toNetWorkPacket.backingBuffer;
            session.packetSent++;
            int sendSize = payloadBuffer.limit() - payloadBuffer.position();
            session.bytesSent += sendSize;
            VPNLog.d(TAG, "DIAG udp send bytes=" + sendSize + " " + ipAndPort);

            try
            {
                if (toNetWorkPacket.udpHeader.destinationPort == 5056 || toNetWorkPacket.udpHeader.sourcePort == 5056)
                {
                    ByteBuffer buffer = deepCopy(payloadBuffer);
                    // 同样只保留真实报文长度，别把缓冲区尾部残留喂给解析器
                    buffer.limit(payloadBuffer.limit());
                    buffer.position(HEADER_SIZE);

                    photonPacketThreading.receivePacketFromOtherThread(buffer);
                }
            }
            catch (Exception ignored)
            {

            }

            if (udpOverProxy)
            {
                byte[] frame = Socks5Client.wrapUdpFrame(originalDestination, originalDestinationPort, payloadBuffer);
                ByteBuffer out = ByteBuffer.wrap(frame);

                while (out.hasRemaining())
                {
                    channel.write(out);
                }

                // 方案 A：节点不支持 UDP 时 ASSOCIATE 能成功但数据被静默丢弃。
                // 发了若干包还一个回包都没有，就判定该出口不可用，切回直连。
                if (session.receiveByteNum == 0 && session.packetSent >= 8)
                {
                    switchToDirect();
                }
            }
            else
            {
                while (payloadBuffer.hasRemaining())
                {
                    channel.write(payloadBuffer);
                }
            }
        }
        catch (IOException e)
        {
            VPNLog.w(TAG, "Network write error: " + ipAndPort, e);
            vpnServer.closeUDPConn(this);
        }
    }

    /** 从 libp2p 包中取出 UDP payload 字节（IP 头长度自适应）。 */
    private static byte[] extractPayload(Packet packet)
    {
        try
        {
            ByteBuffer buffer = packet.backingBuffer;

            if (buffer == null || !buffer.hasArray())
            {
                return null;
            }

            byte[] array = buffer.array();
            int base = buffer.arrayOffset();
            int ipHeaderLength = (array[base] & 0x0F) * 4;
            int payloadOffset = ipHeaderLength + Packet.UDP_HEADER_SIZE;
            int end = base + buffer.limit();

            if (payloadOffset < 0 || base + payloadOffset >= end)
            {
                return null;
            }

            byte[] payload = new byte[end - (base + payloadOffset)];
            System.arraycopy(array, base + payloadOffset, payload, 0, payload.length);
            return payload;
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    /**
     * DNS 查询处理：记录 QTYPE；AAAA 查询直接在本地回 NOERROR/NODATA，
     * 不再转发给上游（本机在 VPN 内没有可用的 IPv6 路由，返回 AAAA 会让游戏偶发连不上）。
     *
     * @return true 表示已本地应答（调用方不要再转发）
     */
    private boolean handleDnsQuery(Packet packet, InetAddress serverAddress, int serverPort)
    {
        byte[] payload = extractPayload(packet);

        if (payload == null)
        {
            DnsStats.recordQuery();
            return false;
        }

        DnsMessage.Query query = DnsMessage.parseQuery(payload, 0, payload.length);
        int qtype = query == null ? -1 : query.qtype;
        DnsStats.recordQuery(qtype, query == null ? null : query.name);

        if (query != null && query.isAaaaQuery())
        {
            byte[] response = DnsMessage.buildNoDataResponse(payload, 0, payload.length);

            if (response != null)
            {
                DnsStats.recordAaaaDropped(query.name);
                sendDnsResponse(response, serverAddress, serverPort);
                return true;
            }
        }

        pendingDns = new PendingDns(payload, serverAddress, serverPort);
        return false;
    }

    /** 用 referencePacket 的头模板构造一个"服务器 -> 客户端"的 DNS 回包并写入 tun 输出队列。 */
    private void sendDnsResponse(byte[] dnsPayload, InetAddress serverAddress, int serverPort)
    {
        try
        {
            ByteBuffer buffer = SocketUtils.getByteBuffer();

            if (dnsPayload.length > buffer.capacity() - HEADER_SIZE)
            {
                VPNLog.w(TAG, "DNS reply too large, drop: " + dnsPayload.length);
                return;
            }

            buffer.position(HEADER_SIZE);
            buffer.put(dnsPayload, 0, dnsPayload.length);

            Packet reply = referencePacket.duplicated();

            if (reply.ip4Header == null || reply.udpHeader == null)
            {
                return;
            }

            reply.ip4Header.sourceAddress = serverAddress;
            reply.ip4Header.destinationAddress = clientAddress;
            reply.udpHeader.sourcePort = serverPort;
            reply.udpHeader.destinationPort = clientPort;

            reply.updateUDPBuffer(buffer, dnsPayload.length);
            buffer.position(HEADER_SIZE + dnsPayload.length);

            outputQueue.offer(reply);
        }
        catch (Throwable e)
        {
            VPNLog.w(TAG, "DNS local reply failed: " + e);
        }
    }

    private void armDnsWatchdog()
    {
        PendingDns pending = pendingDns;

        if (pending == null || closed)
        {
            return;
        }

        cancelDnsWatchdog();

        try
        {
            dnsWatchdogFuture = DNS_WATCHDOG_EXECUTOR.schedule(
                    () -> onDnsWatchdog(pending, 1), DNS_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        }
        catch (Throwable ignored)
        {
        }
    }

    private void cancelDnsWatchdog()
    {
        ScheduledFuture<?> future = dnsWatchdogFuture;

        if (future != null)
        {
            future.cancel(false);
            dnsWatchdogFuture = null;
        }
    }

    /**
     * 上游 DNS 无应答时的兜底链路：重发 -> 仍无应答则回 SERVFAIL。
     * 绝不静默丢弃，客户端要么拿到应答、要么立刻收到 RCODE=2 而快速重试。
     */
    private void onDnsWatchdog(PendingDns expected, int attempt)
    {
        if (closed || pendingDns != expected)
        {
            return;
        }

        if (attempt <= DNS_MAX_RETRY)
        {
            DnsStats.recordRetry();
            VPNLog.w("VPNLog", "DNSWATCH retry attempt=" + attempt + " " + ipAndPort);

            if (writeDnsPayload(expected.payload))
            {
                try
                {
                    dnsWatchdogFuture = DNS_WATCHDOG_EXECUTOR.schedule(
                            () -> onDnsWatchdog(expected, attempt + 1), DNS_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
                    return;
                }
                catch (Throwable ignored)
                {
                }
            }
        }

        DnsStats.recordServfail();
        VPNLog.w("VPNLog", "DNSWATCH no reply, reply SERVFAIL " + ipAndPort);

        byte[] servfail = DnsMessage.buildServFailResponse(expected.payload, 0, expected.payload.length);

        if (servfail != null)
        {
            sendDnsResponse(servfail, expected.serverAddress, expected.serverPort);
        }

        pendingDns = null;
    }

    private boolean writeDnsPayload(byte[] payload)
    {
        DatagramChannel target = channel;

        if (target == null || !target.isConnected())
        {
            return false;
        }

        try
        {
            ByteBuffer buffer = ByteBuffer.wrap(payload);

            while (buffer.hasRemaining())
            {
                target.write(buffer);
            }

            return true;
        }
        catch (Throwable e)
        {
            VPNLog.w(TAG, "DNS retry write failed: " + e);
            return false;
        }
    }

    public void initConnection()
    {
        InetAddress destinationAddress = referencePacket.ip4Header.destinationAddress;
        int destinationPort = referencePacket.udpHeader.destinationPort;

        originalDestination = destinationAddress;
        originalDestinationPort = destinationPort;

        // 53 端口：先做查询分类/AAAA 本地拦截
        if (destinationPort == DNS_PORT)
        {
            if (handleDnsQuery(referencePacket, destinationAddress, destinationPort))
            {
                // AAAA 查询已本地回 NODATA，不需要建通道；把只用于本地应答的隧道移除
                vpnServer.closeUDPConn(this);
                return;
            }
        }

        // 代理开关打开时，游戏 UDP 也走 SOCKS5 UDP 中继（机场）；DNS 除外（保持直连劫持）。
        // 节点不支持 UDP / mihomo 没启动时 initProxyUdp 会失败，自动回退直连（方案 A）。
        boolean useProxy = UpstreamProxyConfig.isEnabled()
                && destinationPort != DNS_PORT
                && !UpstreamProxyConfig.isDirectEndpoint(new InetSocketAddress(destinationAddress, destinationPort));

        if (useProxy)
        {
            udpOverProxy = initProxyUdp(destinationAddress, destinationPort);
        }

        InetAddress channelAddress = destinationAddress;
        int channelPort = destinationPort;

        // 53 端口的查询统一改投国内可达的 223.5.5.5。
        // 注意：只改「实际发包的去向」，referencePacket 仍保持原始目的地址，
        // 这样回包写回 tun 时源地址还是客户端查询的那台 DNS，客户端不会因为
        // 收到"陌生服务器"的应答而丢弃（连接型 UDP socket 会校验源地址）。
        if (!udpOverProxy && destinationPort == DNS_PORT && !DNS_FALLBACK.equals(destinationAddress.getHostAddress()))
        {
            try
            {
                channelAddress = InetAddress.getByName(DNS_FALLBACK);
                channelPort = DNS_PORT;
                DnsStats.recordHijack();
                VPNLog.d(TAG, "DNS redirect " + destinationAddress.getHostAddress() + ":53 -> " + DNS_FALLBACK + " | " + DnsStats.summary());
            }
            catch (Exception e)
            {
                DnsStats.recordFailure();
                VPNLog.w(TAG, "DNS redirect failed: " + e);
            }
        }

        try
        {
            if (!udpOverProxy)
            {
                channel = DatagramChannel.open();
                vpnService.protect(channel.socket());
                channel.configureBlocking(false);
                channel.connect(new InetSocketAddress(channelAddress, channelPort));
            }

            selector.wakeup();
            selectionKey = channel.register(selector,
                    SelectionKey.OP_READ, this);
            VPNLog.d(TAG, "DIAG registered key valid=" + selectionKey.isValid() + " " + ipAndPort);
        }
        catch (Exception e)
        {
            VPNLog.w(TAG, "initConnection failed dest=" + destinationAddress + ":" + destinationPort
                    + " error=" + e);

            // 就绪竞态兜底：DNS 通道没建起来时别静默丢查询，本地回 SERVFAIL 让客户端立刻重试
            if (destinationPort == DNS_PORT && pendingDns != null)
            {
                DnsStats.recordServfail();
                byte[] servfail = DnsMessage.buildServFailResponse(
                        pendingDns.payload, 0, pendingDns.payload.length);

                if (servfail != null)
                {
                    sendDnsResponse(servfail, pendingDns.serverAddress, pendingDns.serverPort);
                }

                pendingDns = null;
            }

            SocketUtils.closeResources(channel);
            channel = null;
            closeUdpControl();
            vpnServer.closeUDPConn(this);
            return;
        }

        referencePacket.swapSourceAndDestination();
        addToNetWorkPacket(referencePacket);

        if (destinationPort == DNS_PORT)
        {
            armDnsWatchdog();
        }
    }

    /**
     * 建立 SOCKS5 UDP 中继：一条 TCP 控制连接做 UDP ASSOCIATE，再用 UDP 直接发给中继地址。
     * 任何一步失败都返回 false，由调用方回退直连。
     */
    private boolean initProxyUdp(InetAddress destination, int destinationPort)
    {
        try
        {
            InetSocketAddress proxy = UpstreamProxyConfig.endpoint();

            udpControlChannel = SocketChannel.open();
            vpnService.protect(udpControlChannel.socket());
            udpControlChannel.configureBlocking(false);
            udpControlChannel.connect(proxy);

            long deadline = System.currentTimeMillis() + 3000;

            while (!udpControlChannel.finishConnect())
            {
                if (System.currentTimeMillis() > deadline)
                {
                    throw new IOException("connect upstream proxy timed out");
                }

                Thread.sleep(1);
            }

            Socks5Client.handshake(udpControlChannel, 3000);

            InetSocketAddress relay = Socks5Client.udpAssociate(udpControlChannel, 3000);
            InetAddress relayAddress = relay.getAddress();

            if (relayAddress == null || relayAddress.isAnyLocalAddress())
            {
                relayAddress = InetAddress.getByName(UpstreamProxyConfig.getHost());
            }

            if (relay.getPort() <= 0)
            {
                throw new IOException("SOCKS5 UDP relay returned no port");
            }

            channel = DatagramChannel.open();
            vpnService.protect(channel.socket());
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(relayAddress, relay.getPort()));

            VPNLog.d(TAG, "UDP over SOCKS5 relay " + relayAddress + ":" + relay.getPort()
                    + " -> " + destination + ":" + destinationPort + " " + ipAndPort);

            return true;
        }
        catch (Exception e)
        {
            VPNLog.w(TAG, "UDP over proxy unavailable, fallback direct: " + e + " " + ipAndPort);
            closeUdpControl();
            SocketUtils.closeResources(channel);
            channel = null;
            return false;
        }
    }

    private void closeUdpControl()
    {
        if (udpControlChannel != null)
        {
            try
            {
                udpControlChannel.close();
            }
            catch (Exception ignored)
            {
            }

            udpControlChannel = null;
        }
    }

    /** 代理 UDP 无回包时切回直连（方案 A）。 */
    private void switchToDirect()
    {
        if (!udpOverProxy)
        {
            return;
        }

        VPNLog.w(TAG, "UDP proxy got no reply, switch to direct: " + ipAndPort);

        // 一致性回退：同一 IP 的 TCP 也钉死直连，避免"登录走机场、游戏走国内"的混合出口被服务器拒绝
        UpstreamProxyConfig.markDirectHost(originalDestination);
        int closedTcp = TcpTunnel.closeAllTo(originalDestination);
        VPNLog.w(TAG, "mark " + originalDestination.getHostAddress() + " as direct, closed "
                + closedTcp + " tcp tunnels");

        try
        {
            closeUdpControl();

            if (channel != null)
            {
                channel.close();
            }

            if (selectionKey != null)
            {
                selectionKey.cancel();
            }

            channel = DatagramChannel.open();
            vpnService.protect(channel.socket());
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(originalDestination, originalDestinationPort));
            udpOverProxy = false;

            selector.wakeup();
            selectionKey = channel.register(selector, SelectionKey.OP_READ, this);
        }
        catch (Exception e)
        {
            VPNLog.w(TAG, "switch to direct failed: " + e);
            SocketUtils.closeResources(channel);
            channel = null;
            vpnServer.closeUDPConn(this);
        }
    }

    public void processPacket(Packet packet)
    {
        // 同一条 UDP 会话上后续的查询：AAAA 也一样本地拦截（客户端可能复用端口换查询类型）
        if (packet.udpHeader != null && packet.udpHeader.destinationPort == DNS_PORT)
        {
            if (handleDnsQuery(packet, packet.ip4Header.destinationAddress, DNS_PORT))
            {
                return;
            }
        }

        addToNetWorkPacket(packet);
        updateInterests();

        if (packet.udpHeader != null && packet.udpHeader.destinationPort == DNS_PORT)
        {
            armDnsWatchdog();
        }
    }

    public void close()
    {
        closed = true;
        pendingDns = null;
        cancelDnsWatchdog();

        try
        {
            closeUdpControl();

            if (selectionKey != null)
            {
                selectionKey.cancel();
            }

            if (channel != null)
            {
                channel.close();
            }

            if (session.appInfo == null && PortHostService.getInstance() != null)
            {
                PortHostService.getInstance().refreshSessionInfo();
            }

            handler.postDelayed(() -> ThreadProxy.getInstance().execute(() ->
            {
                if (session.receiveByteNum == 0 && session.bytesSent == 0)
                {
                    return;
                }

            }), 1000);
        } catch (Exception e) {
            VPNLog.w(TAG, "error to close UDP channel IpAndPort" + ipAndPort + ",error is " + e.getMessage());
        }
    }

    Packet getToNetWorkPackets() {
        return toNetWorkPackets.poll();
    }

    void addToNetWorkPacket(Packet packet)
    {
        toNetWorkPackets.offer(packet);
        updateInterests();
    }

    void updateInterests()
    {
        int ops;

        if (toNetWorkPackets.isEmpty())
        {
            ops = SelectionKey.OP_READ;
        }
        else
        {
            ops = SelectionKey.OP_WRITE | SelectionKey.OP_READ;
        }

        selector.wakeup();
        selectionKey.interestOps(ops);
        VPNLog.d(TAG, "DIAG updateInterests ops=" + ops + " valid=" + selectionKey.isValid() + " " + ipAndPort);

        // VPNLog.d(TAG, "updateInterests ops:" + ops + ",ip" + ipAndPort);
    }

    @Override
    public void onKeyReady(SelectionKey key) {
        processKey(key);
    }

    public Short getPortKey() {
        return portKey;
    }
}
