package com.minhui.vpn.Handlers;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.minhui.vpn.Handlers.HandlerItem.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.stream.Collectors;

public class PlayersHandler
{
    ArrayList<Player> playersInRange = new ArrayList<>();
    private Player localPlayer;

    /** 本地玩家坐标最后一次变化的时刻（用于判断“静止”，反推玩家坐标 XorCode）。 */
    private volatile long localPosLastChangeMs = System.currentTimeMillis();

    public PlayersHandler()
    {
        playersInRange = new ArrayList<>();
        localPlayer = new Player();
    }

    /** 本地玩家坐标距上次变化的毫秒数。 */
    public long localPlayerPosAgeMs()
    {
        return System.currentTimeMillis() - localPosLastChangeMs;
    }

    /**
     * 增量添加/更新一个“发现的玩家”。
     *
     * <p>NewCharacter 会重复下发（换图/重新进入视野），旧实现每次都 add 一个新对象，
     * 列表会重复膨胀且坐标永远是 (0,0)。这里按 id 合并。
     *
     * @param hasPosition 是否已有可用坐标（Move 事件解出来之前为 false）
     */
    public void addOrUpdatePlayer(int id, String nickname, String guildName, String allianceName,
                                  int faction, float currentHealth, float maxHealth,
                                  boolean hasPosition, float posX, float posY)
    {
        SharedLocks.playerHandlerLock.writeLock().lock();

        try
        {
            for (Player player : playersInRange)
            {
                if (player.getId() == id)
                {
                    if (nickname != null && !"null".equals(nickname) && !nickname.isEmpty())
                    {
                        player.nickname = nickname;
                    }

                    if (guildName != null && !"null".equals(guildName) && !guildName.isEmpty())
                    {
                        player.guild = guildName;
                    }

                    if (allianceName != null && !"null".equals(allianceName) && !allianceName.isEmpty())
                    {
                        player.alliance = allianceName;
                    }

                    player.setFaction(faction);

                    if (maxHealth > 0)
                    {
                        player.setHealthSnapshot(currentHealth, maxHealth);
                    }

                    if (hasPosition)
                    {
                        player.setPosX(posX);
                        player.setPosY(posY);
                        player.positionKnown = true;
                    }

                    return;
                }
            }

            Player player = new Player(id, posX, posY, nickname, guildName, currentHealth, maxHealth, 0, 0, null);
            player.alliance = allianceName;
            player.setFaction(faction);
            player.positionKnown = hasPosition;
            playersInRange.add(player);

            if (diagAddCount++ < 20)
            {
                Log.d("RadarDiag", "player added id=" + id + " name=" + nickname + " guild=" + guildName
                        + " alliance=" + allianceName + " faction=" + faction
                        + " total=" + playersInRange.size());
            }
        }
        finally
        {
            SharedLocks.playerHandlerLock.writeLock().unlock();
        }
    }

    private static int diagAddCount;
    private static int diagRemoveCount;

    /** 已发现的玩家数量（不代表都能上雷达，坐标加密的只能算“发现”）。 */
    public int playerCount()
    {
        SharedLocks.playerHandlerLock.readLock().lock();

        try
        {
            return playersInRange.size();
        }
        finally
        {
            SharedLocks.playerHandlerLock.readLock().unlock();
        }
    }

    /** id 是否是已发现的玩家（用于区分 Move 事件是怪还是玩家）。 */
    public boolean containsPlayer(int id)
    {
        SharedLocks.playerHandlerLock.readLock().lock();

        try
        {
            for (Player player : playersInRange)
            {
                if (player.getId() == id)
                {
                    return true;
                }
            }

            return false;
        }
        finally
        {
            SharedLocks.playerHandlerLock.readLock().unlock();
        }
    }

    public  ArrayList<Player> getPlayersInRange()
    {
        SharedLocks.playerHandlerLock.readLock().lock();

        try
        {
            return new ArrayList<>(playersInRange);
        }
        finally
        {
            SharedLocks.playerHandlerLock.readLock().unlock();
        }
    }

    public void addPlayer(float posX, float posY, int id, String nickname, String guildName, float currentHealth, float maxHealth, float currentEnergy, float maxEnergy, short[] items)
    {
        SharedLocks.playerHandlerLock.writeLock().lock();

        try
        {
            Player player = new Player(id, posX, posY, nickname, guildName, currentHealth, maxHealth, currentEnergy, maxEnergy, items);
            playersInRange.add(player);
        }
        finally
        {
            SharedLocks.playerHandlerLock.writeLock().unlock();
        }
    }
    public void updatePlayerMounted(int id, boolean mounted)
    {
        SharedLocks.playerHandlerLock.writeLock().lock();

        try
        {
            for (Player player : playersInRange)
            {
                if (player.getId() == id)
                {
                    player.setMounted(mounted);
                    break;
                }
            }
        }
        finally
        {
            SharedLocks.playerHandlerLock.writeLock().unlock();
        }
    }

    public  void removePlayer(int id)
    {
        SharedLocks.playerHandlerLock.writeLock().lock();

        try
        {
            boolean removed = playersInRange.removeIf(player -> player.getId() == id);

            if (removed && diagRemoveCount++ < 20)
            {
                Log.d("RadarDiag", "player removed id=" + id + " total=" + playersInRange.size());
            }
        }
        finally
        {
            SharedLocks.playerHandlerLock.writeLock().unlock();
        }
    }

    public void updateLocalPlayerPosition(float posX, float posY, float angle, float targetposX, float targetposY, float speed)
    {
        synchronized (SharedLocks.localPlayerLock)
        {
            if (posX != localPlayer.getPosX() || posY != localPlayer.getPosY())
            {
                localPosLastChangeMs = System.currentTimeMillis();
            }

            localPlayer.setPosX(posX);
            localPlayer.setPosY(posY);

            localPlayer.setAngle(angle);

            localPlayer.setTargetPosX(targetposX);
            localPlayer.setTargetPosY(targetposY);

            localPlayer.setSpeed(speed);
        }
    }

    /**
     * 用“移动同步事件”里的坐标更新本地玩家：该事件对场上每个实体的坐标广播，
     * 里面也包含本地玩家自己，id 与 Join 拿到的 ObjectId 相同。
     */
    public void updateLocalPlayerPositionById(int id, float posX, float posY)
    {
        synchronized (SharedLocks.localPlayerLock)
        {
            if (localPlayer.getId() == id)
            {
                if (posX != localPlayer.getPosX() || posY != localPlayer.getPosY())
                {
                    localPosLastChangeMs = System.currentTimeMillis();
                }

                localPlayer.setPosX(posX);
                localPlayer.setPosY(posY);
            }
        }
    }

    public void updateLocalPlayer(int characterId, int[] markId, String nickname, String guildName, String cluster, float posX, float posY,
                                  float angle, float currentHealth, float maxHealth, float currentEnergy, float maxEnergy, int faction)
    {
        synchronized (SharedLocks.localPlayerLock)
        {
            localPlayer.Update(characterId, markId, nickname, guildName, cluster, posX, posY, angle,
                    currentHealth, maxHealth, currentEnergy, maxEnergy, faction);
        }
    }

    public String localPlayerGuildName()
    {
        synchronized (SharedLocks.localPlayerLock)
        {
            return localPlayer.getGuild();
        }
    }

    public void updateLocalPlayerAlliance(String allianceName)
    {
        synchronized (SharedLocks.localPlayerLock)
        {
            if (allianceName != null && !allianceName.isEmpty())
            {
                localPlayer.setAlliance(allianceName);
            }
        }
    }

    public String localPlayerAlliance()
    {
        synchronized (SharedLocks.localPlayerLock)
        {
            return localPlayer.getAlliance();
        }
    }

    public float localPlayerFaction()
    {
        synchronized (SharedLocks.localPlayerLock)
        {
            return localPlayer.getFaction();
        }
    }


    public int localPlayerId()
    {
        synchronized (SharedLocks.localPlayerLock)
        {
            return localPlayer.getId();
        }
    }

    public  float localPlayerPosX()
    {
        synchronized (SharedLocks.localPlayerLock)
        {
            return localPlayer.getPosX();
        }
    }

    public   float localPlayerPosY()
    {
        synchronized (SharedLocks.localPlayerLock)
        {
            return localPlayer.getPosY();
        }
    }

    public  void updatePlayerPosition(int id, float posX, float posY)
    {
        SharedLocks.playerHandlerLock.writeLock().lock();

        try
        {
            for (Player player : playersInRange)
            {
                if (player.getId() == id)
                {
                    player.setPosX(posX);
                    player.setPosY(posY);
                    player.positionKnown = true;
                }
            }
        }
        finally
        {
            SharedLocks.playerHandlerLock.writeLock().unlock();
        }
    }

    public void updatePlayerHealth(int id, float currentHealth)
    {
        // 与 getPlayersInRange()/addPlayer() 共用同一把锁：解析线程写、渲染线程读，
        // 不加锁遍历 ArrayList 有概率抛 ConcurrentModificationException 闪退
        SharedLocks.playerHandlerLock.writeLock().lock();

        try
        {
            for (Player player : playersInRange)
            {
                if (player.getId() == id)
                {
                    player.setCurrentHealth(currentHealth);
                    break;
                }
            }
        }
        finally
        {
            SharedLocks.playerHandlerLock.writeLock().unlock();
        }
    }

    public void updatePlayerMaxHealth(int id, float maxHealth)
    {
        SharedLocks.playerHandlerLock.writeLock().lock();

        try
        {
            for (Player player : playersInRange)
            {
                if (player.getId() == id)
                {
                    player.setHealthSnapshot(player.getCurrentHealth(), maxHealth);
                    break;
                }
            }
        }
        finally
        {
            SharedLocks.playerHandlerLock.writeLock().unlock();
        }
    }


    public void clear()
    {
        SharedLocks.playerHandlerLock.writeLock().lock();

        try
        {
            Log.d("RadarDiag", "players CLEARED, was=" + playersInRange.size());
            playersInRange.clear();
        }
        finally
        {
            SharedLocks.playerHandlerLock.writeLock().unlock();
        }
    }
}

