package com.minhui.vpn.tunnel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小 DNS 报文解析/改写工具（纯 Java，不依赖 Android，可离线单测）。
 *
 * <p>用途：
 * <ul>
 *   <li>{@link #parseQuery(byte[], int, int)}：识别标准查询的 QTYPE，用来拦截 AAAA(28)；</li>
 *   <li>{@link #buildNoDataResponse(byte[], int, int)}：对 AAAA 查询本地回 NOERROR/NODATA，
 *       让客户端拿不到任何 IPv6 地址（本机 IPv6 在 VPN 内不可路由）；</li>
 *   <li>{@link #buildServFailResponse(byte[], int, int)}：DNS 上游在就绪竞态期间不可用时快速失败，
 *       让系统解析器立刻换服务器重试，而不是等 5s 超时；</li>
 *   <li>{@link #stripAaaaRecords(byte[], int, int)}：兜底把上游应答里的 AAAA 记录剥离。</li>
 * </ul>
 *
 * <p>所有方法在格式异常时返回 null（调用方原样转发），绝不因解析异常影响正常 DNS。
 * 剥离 AAAA 时会把压缩指针域名全部展开、重建应答，避免"删记录导致偏移错位、指针指错"。
 */
public final class DnsMessage
{
    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;
    public static final int CLASS_IN = 1;

    public static final int RCODE_NOERROR = 0;
    public static final int RCODE_SERVFAIL = 2;

    private static final int HEADER_SIZE = 12;
    private static final int FLAG_QR = 0x8000;
    /** 标准应答标志：QR=1、RD=1、RA=1、RCODE=0。 */
    private static final int FLAGS_NOERROR_RESPONSE = 0x8180;
    /** 标准应答标志 + RCODE=SERVFAIL。 */
    private static final int FLAGS_SERVFAIL_RESPONSE = 0x8182;
    /** 重建应答的最大长度，超过就放弃改写（原样转发），避免触碰客户端 UDP 缓冲。 */
    private static final int MAX_REBUILT_LENGTH = 4096;

    private DnsMessage()
    {
    }

    /** 单问题标准查询的解析结果。 */
    public static final class Query
    {
        public final int id;
        public final int flags;
        public final int qtype;
        public final int qclass;
        /** 问题段结束位置（绝对下标，指向 qclass 之后）。 */
        public final int questionEnd;
        public final String name;

        Query(int id, int flags, int qtype, int qclass, int questionEnd, String name)
        {
            this.id = id;
            this.flags = flags;
            this.qtype = qtype;
            this.qclass = qclass;
            this.questionEnd = questionEnd;
            this.name = name;
        }

        public boolean isAaaaQuery()
        {
            return qtype == TYPE_AAAA && qclass == CLASS_IN;
        }
    }

    public static String typeName(int type)
    {
        switch (type)
        {
            case TYPE_A:
                return "A";
            case TYPE_AAAA:
                return "AAAA";
            case 5:
                return "CNAME";
            case 65:
                return "HTTPS";
            case 64:
                return "SVCB";
            default:
                return String.valueOf(type);
        }
    }

    /**
     * 解析「单个问题 + 标准查询（QR=0）」；其它情况返回 null（不拦截）。
     */
    public static Query parseQuery(byte[] data, int off, int len)
    {
        if (data == null || len < HEADER_SIZE || off < 0 || off + len > data.length)
        {
            return null;
        }

        try
        {
            Reader reader = new Reader(data, off, len);
            int id = reader.u16(off);
            int flags = reader.u16(off + 2);

            if ((flags & FLAG_QR) != 0)
            {
                return null; // 不是查询
            }

            int qdCount = reader.u16(off + 4);

            if (qdCount != 1)
            {
                return null; // 多问题/零问题异常包不拦截
            }

            int p = off + HEADER_SIZE;
            String name = reader.nameText(p);
            p = reader.skipName(p);

            if (p + 4 > off + len)
            {
                return null;
            }

            int qtype = reader.u16(p);
            int qclass = reader.u16(p + 2);
            return new Query(id, flags, qtype, qclass, p + 4, name);
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    /**
     * 生成 NOERROR/NODATA 应答：保留 query ID，QDCOUNT=1、ANCOUNT=0、flags=0x8180，
     * 原问题段逐字节带回（偏移不变，问题名即使带压缩指针也仍然有效）。
     *
     * @return 应答报文，或 null（不是标准单问题查询）
     */
    public static byte[] buildNoDataResponse(byte[] data, int off, int len)
    {
        Query query = parseQuery(data, off, len);

        if (query == null)
        {
            return null;
        }

        int questionLength = query.questionEnd - (off + HEADER_SIZE);

        if (questionLength < 0)
        {
            return null;
        }

        byte[] response = new byte[HEADER_SIZE + questionLength];
        System.arraycopy(data, off, response, 0, response.length);
        response[2] = (byte) ((FLAGS_NOERROR_RESPONSE >> 8) & 0xFF);
        response[3] = (byte) (FLAGS_NOERROR_RESPONSE & 0xFF);
        response[4] = 0;
        response[5] = 1; // QDCOUNT
        response[6] = 0;
        response[7] = 0; // ANCOUNT
        response[8] = 0;
        response[9] = 0; // NSCOUNT
        response[10] = 0;
        response[11] = 0; // ARCOUNT
        return response;
    }

    /** 生成 SERVFAIL 应答（尽力带回原问题段），用于 DNS 上游不可用时快速失败。 */
    public static byte[] buildServFailResponse(byte[] data, int off, int len)
    {
        if (data == null || len < HEADER_SIZE || off < 0 || off + len > data.length)
        {
            return null;
        }

        Query query = parseQuery(data, off, len);
        int questionLength = query == null ? 0 : query.questionEnd - (off + HEADER_SIZE);

        if (questionLength < 0)
        {
            questionLength = 0;
        }

        byte[] response = new byte[HEADER_SIZE + questionLength];

        if (questionLength > 0)
        {
            System.arraycopy(data, off, response, 0, response.length);
        }
        else
        {
            System.arraycopy(data, off, response, 0, HEADER_SIZE);
        }

        response[2] = (byte) ((FLAGS_SERVFAIL_RESPONSE >> 8) & 0xFF);
        response[3] = (byte) (FLAGS_SERVFAIL_RESPONSE & 0xFF);
        response[4] = 0;
        response[5] = (byte) (questionLength > 0 ? 1 : 0);
        response[6] = 0;
        response[7] = 0;
        response[8] = 0;
        response[9] = 0;
        response[10] = 0;
        response[11] = 0;
        return response;
    }

    /** 应答摘要，用于诊断日志。 */
    public static final class ResponseSummary
    {
        public final String name;
        public final int rcode;
        public final int aRecords;
        public final int aaaaRecords;

        ResponseSummary(String name, int rcode, int aRecords, int aaaaRecords)
        {
            this.name = name;
            this.rcode = rcode;
            this.aRecords = aRecords;
            this.aaaaRecords = aaaaRecords;
        }

        @Override
        public String toString()
        {
            return "name=" + name + " rcode=" + rcode + " a=" + aRecords + " aaaa=" + aaaaRecords;
        }
    }

    /** 应答摘要：域名、rcode、A/AAAA 记录数，用于诊断日志。 */
    public static ResponseSummary describeResponse(byte[] data, int off, int len)
    {
        try
        {
            Reader reader = new Reader(data, off, len);
            int flags = reader.u16(off + 2);

            if ((flags & FLAG_QR) == 0)
            {
                return null;
            }

            int qdCount = reader.u16(off + 4);
            int anCount = reader.u16(off + 6);
            int rcode = flags & 0x0F;
            int p = off + HEADER_SIZE;
            String name = "";

            for (int i = 0; i < qdCount; i++)
            {
                if (i == 0)
                {
                    name = reader.nameText(p);
                }

                p = reader.skipName(p) + 4;
            }

            int aRecords = 0;
            int aaaaRecords = 0;

            for (int i = 0; i < anCount; i++)
            {
                p = reader.skipName(p);
                int type = reader.u16(p);
                p += 8; // type(2) class(2) ttl(4)
                int rdLength = reader.u16(p);
                p += 2;

                if (type == TYPE_A)
                {
                    aRecords++;
                }
                else if (type == TYPE_AAAA)
                {
                    aaaaRecords++;
                }

                p += rdLength;

                if (p > off + len)
                {
                    break;
                }
            }

            return new ResponseSummary(name, rcode, aRecords, aaaaRecords);
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    /**
     * 剥离应答中的 AAAA 记录（会调整各段计数并重建报文）。
     *
     * <p>实现要点：解析时把压缩指针域名展开成普通 label 串、按已知 rdata 结构重建名字，
     * 输出报文不再含压缩指针，因此删除记录不会破坏后续记录。任何不确定结构（未知 rdata 类型）
     * 或长度异常时返回 null，调用方原样转发。
     *
     * @return 改写后的应答；无 AAAA 或无法安全改写时返回 null
     */
    public static byte[] stripAaaaRecords(byte[] data, int off, int len)
    {
        if (data == null || len < HEADER_SIZE || off < 0 || off + len > data.length)
        {
            return null;
        }

        try
        {
            Reader reader = new Reader(data, off, len);
            int id = reader.u16(off);
            int flags = reader.u16(off + 2);

            if ((flags & FLAG_QR) == 0)
            {
                return null;
            }

            int qdCount = reader.u16(off + 4);
            int anCount = reader.u16(off + 6);
            int nsCount = reader.u16(off + 8);
            int arCount = reader.u16(off + 10);

            int p = off + HEADER_SIZE;
            List<byte[]> questions = new ArrayList<>(qdCount);

            for (int i = 0; i < qdCount; i++)
            {
                int start = p;
                p = reader.skipName(p) + 4;
                questions.add(reader.slice(start, p));
            }

            int[] cursor = new int[]{p};
            List<Rr> answers = readRecords(reader, cursor, anCount);
            List<Rr> authority = readRecords(reader, cursor, nsCount);
            List<Rr> additional = readRecords(reader, cursor, arCount);

            int removed = filterAaaa(answers) + filterAaaa(authority) + filterAaaa(additional);

            if (removed == 0)
            {
                return null;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(len);

            writeU16(out, id);
            writeU16(out, flags);
            writeU16(out, qdCount);
            writeU16(out, answers.size());
            writeU16(out, authority.size());
            writeU16(out, additional.size());

            for (byte[] question : questions)
            {
                out.write(question, 0, question.length);
            }

            writeRecords(out, answers);
            writeRecords(out, authority);
            writeRecords(out, additional);

            if (out.size() > MAX_REBUILT_LENGTH)
            {
                return null;
            }

            return out.toByteArray();
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private static final class Rr
    {
        byte[] name;
        int type;
        int rrClass;
        long ttl;
        byte[] rdata;
    }

    private static int filterAaaa(List<Rr> records)
    {
        int removed = 0;

        for (int i = records.size() - 1; i >= 0; i--)
        {
            Rr rr = records.get(i);

            if (rr.type == TYPE_AAAA && rr.rrClass == CLASS_IN)
            {
                records.remove(i);
                removed++;
            }
        }

        return removed;
    }

    private static List<Rr> readRecords(Reader reader, int[] cursor, int count) throws IOException
    {
        List<Rr> records = new ArrayList<>(Math.max(0, count));
        int p = cursor[0];

        for (int i = 0; i < count; i++)
        {
            Rr rr = new Rr();
            rr.name = reader.nameWire(p);
            p = reader.skipName(p);
            rr.type = reader.u16(p);
            rr.rrClass = reader.u16(p + 2);
            rr.ttl = reader.u32(p + 4);
            int rdLength = reader.u16(p + 8);
            int rdStart = p + 10;

            if (rdStart + rdLength > reader.limit)
            {
                throw new IOException("rdata truncated");
            }

            rr.rdata = convertRdata(reader, rr.type, rdStart, rdLength);
            records.add(rr);
            p = rdStart + rdLength;
        }

        cursor[0] = p;
        return records;
    }

    /**
     * 把 rdata 转成"不含压缩指针"的字节。只对结构已知的类型做展开；
     * 遇到未知类型直接抛异常 -> 上层返回 null 原样转发，避免指针错位。
     */
    private static byte[] convertRdata(Reader reader, int type, int start, int length) throws IOException
    {
        switch (type)
        {
            case TYPE_A:      // 4 字节 IPv4，无名字
            case TYPE_AAAA:   // 16 字节 IPv6，无名字
            case 16:          // TXT
            case 41:          // OPT（EDNS0，rdata 是 option，无压缩名字）
            case 43:          // DS
            case 46:          // RRSIG
            case 47:          // NSEC
            case 48:          // DNSKEY
            case 65:          // SVCB / HTTPS：target name 不允许压缩
            case 64:          // SVCB
                return reader.slice(start, start + length);

            case 2:           // NS
            case 5:           // CNAME
            case 12:          // PTR
            case 39:          // DNAME
                return reader.nameWire(start);

            case 15:          // MX：preference(2) + exchange name
            {
                byte[] name = reader.nameWire(start + 2);
                byte[] rdata = new byte[2 + name.length];
                rdata[0] = reader.data[start];
                rdata[1] = reader.data[start + 1];
                System.arraycopy(name, 0, rdata, 2, name.length);
                return rdata;
            }

            case 33:          // SRV：priority(2) weight(2) port(2) + target name
            {
                byte[] name = reader.nameWire(start + 6);
                byte[] rdata = new byte[6 + name.length];
                System.arraycopy(reader.data, start, rdata, 0, 6);
                System.arraycopy(name, 0, rdata, 6, name.length);
                return rdata;
            }

            case 6:           // SOA：mname + rname + 20 字节定长
            {
                int p = start;
                byte[] mname = reader.nameWire(p);
                p = reader.skipName(p);
                byte[] rname = reader.nameWire(p);
                p = reader.skipName(p);

                if (p + 20 > reader.limit)
                {
                    throw new IOException("SOA truncated");
                }

                byte[] rdata = new byte[mname.length + rname.length + 20];
                System.arraycopy(mname, 0, rdata, 0, mname.length);
                System.arraycopy(rname, 0, rdata, mname.length, rname.length);
                System.arraycopy(reader.data, p, rdata, mname.length + rname.length, 20);
                return rdata;
            }

            case 14:          // MINFO：rmailbx + emailbx
            {
                byte[] first = reader.nameWire(start);
                int p = reader.skipName(start);
                byte[] second = reader.nameWire(p);
                byte[] rdata = new byte[first.length + second.length];
                System.arraycopy(first, 0, rdata, 0, first.length);
                System.arraycopy(second, 0, rdata, first.length, second.length);
                return rdata;
            }

            default:
                throw new IOException("unsupported rdata type " + type);
        }
    }

    private static void writeRecords(ByteArrayOutputStream out, List<Rr> records)
    {
        for (Rr rr : records)
        {
            out.write(rr.name, 0, rr.name.length);
            writeU16(out, rr.type);
            writeU16(out, rr.rrClass);
            writeU32(out, rr.ttl);
            writeU16(out, rr.rdata.length);
            out.write(rr.rdata, 0, rr.rdata.length);
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int value)
    {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, long value)
    {
        out.write((int) ((value >> 24) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    /** 带边界检查的 DNS 报文读取器；所有下标都是 data 数组的绝对下标。 */
    private static final class Reader
    {
        final byte[] data;
        final int base;
        final int limit;

        Reader(byte[] data, int off, int len)
        {
            this.data = data;
            this.base = off;
            this.limit = off + len;
        }

        int u8(int p) throws IOException
        {
            if (p < base || p >= limit)
            {
                throw new IOException("dns read out of range");
            }

            return data[p] & 0xFF;
        }

        int u16(int p) throws IOException
        {
            return (u8(p) << 8) | u8(p + 1);
        }

        long u32(int p) throws IOException
        {
            return ((long) u16(p) << 16) | u16(p + 2);
        }

        byte[] slice(int start, int end) throws IOException
        {
            if (start < base || end > limit || end < start)
            {
                throw new IOException("dns slice out of range");
            }

            byte[] out = new byte[end - start];
            System.arraycopy(data, start, out, 0, out.length);
            return out;
        }

        /** 跳过（可含压缩指针的）域名，返回名字之后的位置。 */
        int skipName(int p) throws IOException
        {
            int jumps = 0;

            while (true)
            {
                int length = u8(p);

                if (length == 0)
                {
                    return p + 1;
                }

                if ((length & 0xC0) == 0xC0)
                {
                    int pointer = ((length & 0x3F) << 8) | u8(p + 1);

                    if (base + pointer >= limit)
                    {
                        throw new IOException("dns pointer out of range");
                    }

                    return p + 2;
                }

                if ((length & 0xC0) != 0)
                {
                    throw new IOException("bad dns label length");
                }

                p += 1 + length;

                if (p > limit || ++jumps > 127)
                {
                    throw new IOException("dns name too long");
                }
            }
        }

        /** 把域名展开成不带压缩指针的 wire 字节（label 长度 + label 内容 + 0）。 */
        byte[] nameWire(int p) throws IOException
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16);
            int jumps = 0;

            while (true)
            {
                int length = u8(p);

                if (length == 0)
                {
                    out.write(0);
                    return out.toByteArray();
                }

                if ((length & 0xC0) == 0xC0)
                {
                    int pointer = ((length & 0x3F) << 8) | u8(p + 1);
                    p = base + pointer;

                    if (p < base || p >= limit || ++jumps > 127)
                    {
                        throw new IOException("bad dns pointer");
                    }

                    continue;
                }

                if ((length & 0xC0) != 0)
                {
                    throw new IOException("bad dns label length");
                }

                if (p + 1 + length > limit)
                {
                    throw new IOException("dns label truncated");
                }

                for (int i = 0; i <= length; i++)
                {
                    out.write(data[p + i]);
                }

                p += 1 + length;

                if (out.size() > 255)
                {
                    throw new IOException("dns name too long");
                }
            }
        }

        /** 可读域名（点分）；解析失败返回空串。 */
        String nameText(int p)
        {
            StringBuilder builder = new StringBuilder();
            int jumps = 0;

            try
            {
                while (true)
                {
                    int length = u8(p);

                    if (length == 0)
                    {
                        break;
                    }

                    if ((length & 0xC0) == 0xC0)
                    {
                        int pointer = ((length & 0x3F) << 8) | u8(p + 1);
                        p = base + pointer;

                        if (++jumps > 127)
                        {
                            break;
                        }

                        continue;
                    }

                    if ((length & 0xC0) != 0 || p + 1 + length > limit)
                    {
                        break;
                    }

                    if (builder.length() > 0)
                    {
                        builder.append('.');
                    }

                    for (int i = 1; i <= length; i++)
                    {
                        int c = data[p + i] & 0xFF;
                        builder.append(c >= 32 && c < 127 ? (char) c : '?');
                    }

                    p += 1 + length;

                    if (builder.length() > 255)
                    {
                        break;
                    }
                }
            }
            catch (Throwable ignored)
            {
            }

            return builder.toString();
        }
    }
}
