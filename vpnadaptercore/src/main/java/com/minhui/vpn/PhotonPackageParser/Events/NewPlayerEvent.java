package com.minhui.vpn.PhotonPackageParser.Events;

import android.util.Log;

import com.google.gson.Gson;
import com.minhui.vpn.Handlers.MainHandler;
import com.minhui.vpn.PhotonPackageParser.Utils;

import java.util.HashMap;

public class NewPlayerEvent
{
    final Gson gson = new Gson();

    public  NewPlayerEvent()
    {

    }

    public void removePlayer(HashMap<Object, Object> Parameters)
    {
        MainHandler.getInstance().playersHandler.removePlayer(Utils.getNumber(Parameters.get(0)));
    }

    public void NewCharacter(HashMap<Object, Object> Parameters)
    {
        // 当前协议（1.32）的 NewCharacter 参数：0=ObjectId, 1=名字, 7=Guid, 8=公会, 40=装备, 51=联盟。
        // 旧代码把 14 当坐标、20/21 当血量，这些字段现在根本不存在，
        // getFloats(null) 直接抛异常，玩家一个都加不进去（players 恒为 0）。
        // 坐标由 Move 事件（code 3 的字节数组）随后补上。
        int id = Utils.getNumber(Parameters.get(0));
        String name = Utils.getString(Parameters.get(1));
        String guildName = Utils.getString(Parameters.get(8));

        MainHandler.getInstance().playersHandler.addPlayer(0, 0, id, name, guildName,
                0, 0, 0, 0, null);
    }

    public void updatePlayerPosition(int id, float posX , float posY)
    {
        MainHandler.getInstance().playersHandler.updatePlayerPosition(id, posX, posY);
    }

    public void updateLocalPlayer(HashMap<Object, Object> parameters)
    {
        // 当前协议的 Join 响应：0=ObjectId, 2=用户名, 8=地图, 58=公会, 64=入场坐标, 79=联盟。
        // 旧代码取的 9=坐标 / 11,12=血量 / 16,17=能量 / 43=阵营 / 57=公会 在 1.32 已经不存在，
        // getFloats(9) 会抛异常，本地玩家一直更新失败（lp 恒为 0）。
        int id = Utils.getNumber(parameters.get(0));
        String cName = Utils.getString(parameters.get(2));
        String cluster = Utils.getString(parameters.get(8));
        String guildName = Utils.getString(parameters.get(58));

        float[] pos = safeFloats(parameters.get(64));
        float posX = pos != null ? pos[0] : 0;
        float posY = pos != null ? pos[1] : 0;

        MainHandler.getInstance().playersHandler.updateLocalPlayer(id, null, cName, guildName,
                cluster, posX, posY, 0, 0, 0, 0, 0, 0);
    }

    /** 安全取 float[2]：字段缺失或类型不对时返回 null，不再让整个 Join 处理失败。 */
    private static float[] safeFloats(Object value)
    {
        try
        {
            float[] result = Utils.getFloats(value);

            return result != null && result.length >= 2 ? result : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public void Mounted(HashMap<Object, Object> parameters)
    {
        int id = Utils.getNumber(parameters.get(0));
        String ten = Utils.getString(parameters.get(8));
        boolean mounted = Utils.getBoolean(parameters.get(11));

        if(mounted)
        {
            MainHandler.getInstance().playersHandler.updatePlayerMounted(id, true);
        }
        else if (ten != null && ten.equals("-1"))
        {
            MainHandler.getInstance().playersHandler.updatePlayerMounted(id, true);
        }
        else
        {
            MainHandler.getInstance().playersHandler.updatePlayerMounted(id, false);
        }
    }

    public void InCombatStateUpdate(HashMap<Object, Object> parameters)
    {
        int playerId = Utils.getNumber(parameters.get(0));
        boolean playerAttacking =  Utils.getBoolean(parameters.get(1));
        boolean enemyAttacking  =  Utils.getBoolean(parameters.get(2));
    }

    public void InventoryPutItem(HashMap<Object, Object> parameters)
    {
        int id = Utils.getNumber(parameters.get(0));
    }

    public void NewMount(HashMap<Object, Object> parameters)
    {
        int id =  Utils.getNumber(parameters.get(0));
        float[] pos =  Utils.getFloats(parameters.get(3));
        byte[] items = Utils.getByteArray(parameters.get(5));
    }

    public void UpdateMoney(HashMap<Object, Object> parameters)
    {
        int id =  Utils.getNumber(parameters.get(0));
        String silver =  Utils.getString(parameters.get(1));

        int len = silver.length() - 4;
        long currentSilver = Long.parseLong(silver.substring(0, len));
    }

    public void HealthUpdate(HashMap<Object, Object> parameters)
    {
        int attackerEntityId = Utils.getNumber(parameters.get(0));
        int targetHealthUpdate = Utils.getNumber(parameters.get(2));
        int targetCurrentHealth = Utils.getNumber(parameters.get(3));
        int targetEntityId = Utils.getNumber(parameters.get(6));

    }

    public void Attack(HashMap<Object, Object> parameters)
    {
        int attackerID = Utils.getNumber(parameters.get(0));
        int targetID = Utils.getNumber(parameters.get(2));
    }

    public void NewBuilding(HashMap<Object, Object> parameters)
    {
        int packetID =  Utils.getNumber(parameters.get(0));
        String name =  Utils.getString(parameters.get(3));
        float[] pos =  Utils.getFloats(parameters.get(4));
    }
}
