package com.minhui.vpn.PhotonPackageParser;

import android.util.Log;

import com.minhui.vpn.PhotonPackageParser.classes.PhotonPacketParser;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class PhotonPacketThreading
{
    private static final String TAG = "PhotonPacketThreading";

    /** 解析追不上时直接丢新包：无界队列在进世界/大范围事件时会把进程内存吃光。 */
    private static final int MAX_QUEUED_PACKETS = 512;

    private ExecutorService executor;
    private LinkedBlockingQueue<ByteBuffer> packetQueue;
    private PhotonPacketParser photonPacketParser = new PhotonPacketParser();

    public PhotonPacketThreading()
    {
        int numThreads = Runtime.getRuntime().availableProcessors();
        executor = Executors.newFixedThreadPool(numThreads);
        packetQueue = new LinkedBlockingQueue<>();
    }

    public void receivePacketFromOtherThread(ByteBuffer packet)
    {
        synchronized (packetQueue)
        {
            if (packetQueue.size() >= MAX_QUEUED_PACKETS)
            {
                return;
            }

            packetQueue.offer(packet);

            if (packetQueue.size() == 1)
            {
                processNextPacket();
            }
        }
    }

    private void processNextPacket()
    {
        executor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                ByteBuffer packet;

                synchronized (packetQueue)
                {
                    packet = packetQueue.poll();
                }

                if (packet != null)
                {
                    try
                    {
                        photonPacketParser.receive(packet);
                    }
                    catch (Throwable t)
                    {
                        // 解析线程的异常不能把整个进程带走：进程一死 VpnService 跟着没了，
                        // 游戏的连接会瞬间断开（表现为"一进世界就断网"）。
                        // 这里连 Error 一起兜住，损坏的包丢弃即可。
                        Log.e(TAG, "photon packet dropped: " + t);
                    }

                    processNextPacket();
                }
            }
        });
    }
    public void shutdown() {
        executor.shutdown();
    }
}