using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace NebulaUpdater.Core.Services;

/// <summary>Ping / 连通性探测。</summary>
public static class PingService
{
    /// <summary>ICMP Ping 一个主机，返回毫秒数或错误</summary>
    public static async Task<PingResult> PingHostAsync(string host, CancellationToken ct = default)
    {
        try
        {
            using var ping = new System.Net.NetworkInformation.Ping();
            var reply = await ping.SendPingAsync(host, 5000).WaitAsync(ct).ConfigureAwait(false);
            return reply.Status == IPStatus.Success
                ? new PingResult(true, $"{reply.RoundtripTime} ms")
                : new PingResult(false, reply.Status.ToString(), reply.Status.ToString());
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            return new PingResult(false, ex.Message, ex.Message);
        }
    }

    /// <summary>
    /// 查询 MC 服务器状态（走 Server List Ping 协议，不依赖 Python）。
    ///
    /// 旧版靠 Python + mcstatus，需要额外下载扩展包；
    /// 这里直接用 TCP + VarInt 实现，零依赖、启动即用。
    /// </summary>
    public static async Task<McServerStatus?> QueryMcServerAsync(
        string host, int port = 25565, CancellationToken ct = default)
    {
        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(host, port, ct).ConfigureAwait(false);
            client.ReceiveTimeout = 5000;

            await using var stream = client.GetStream();

            // Handshake: protocol=-1(只查状态) address port nextState=1
            await using var ms = new MemoryStream();
            WriteVarInt(ms, 0x00);        // packet id
            WriteVarInt(ms, -1);          // protocol version
            WriteString(ms, host);
            WriteShort(ms, (short)port);
            WriteVarInt(ms, 1);           // next state: status

            await WriteVarIntPrefixedAsync(stream, ms.ToArray(), ct).ConfigureAwait(false);
            await WriteVarIntPrefixedAsync(stream, new byte[] { 0x00 }, ct).ConfigureAwait(false); // status request

            var length = await ReadVarIntAsync(stream, ct).ConfigureAwait(false);
            if (length <= 0) return null;
            var buf = new byte[length];
            var read = 0;
            while (read < length)
            {
                var n = await stream.ReadAsync(buf.AsMemory(read, length - read), ct).ConfigureAwait(false);
                if (n <= 0) break;
                read += n;
            }
            if (read < length) return null;

            using var rms = new MemoryStream(buf);
            ReadVarIntFromStream(rms);    // packet id
            var jsonLen = ReadVarIntFromStream(rms);
            var jsonBytes = new byte[jsonLen];
            rms.ReadExactly(jsonBytes, 0, jsonLen);
            var json = System.Text.Encoding.UTF8.GetString(jsonBytes);

            using var doc = System.Text.Json.JsonDocument.Parse(json);
            var rootEl = doc.RootElement;
            var version = rootEl.TryGetProperty("version", out var v) && v.TryGetProperty("name", out var vn)
                ? vn.GetString() ?? "?" : "?";
            var online = rootEl.TryGetProperty("players", out var p) && p.TryGetProperty("online", out var o)
                ? o.GetInt32() : 0;
            var max = rootEl.TryGetProperty("players", out var p2) && p2.TryGetProperty("max", out var m)
                ? m.GetInt32() : 0;

            return new McServerStatus(version, online, max);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return null;
        }
    }

    // ---- VarInt 编解码（Minecraft 协议） ----
    private static void WriteVarInt(Stream s, int value)
    {
        var v = (uint)value;
        do
        {
            var b = (byte)(v & 0x7F);
            v >>= 7;
            if (v != 0) b |= 0x80;
            s.WriteByte(b);
        } while (v != 0);
    }

    private static void WriteString(Stream s, string text)
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(text);
        WriteVarInt(s, bytes.Length);
        s.Write(bytes);
    }

    private static void WriteShort(Stream s, short value)
    {
        s.WriteByte((byte)(value >> 8));
        s.WriteByte((byte)(value & 0xFF));
    }

    private static async Task WriteVarIntPrefixedAsync(Stream s, byte[] payload, CancellationToken ct)
    {
        using var header = new MemoryStream();
        WriteVarInt(header, payload.Length);
        await s.WriteAsync(header.ToArray(), ct).ConfigureAwait(false);
        await s.WriteAsync(payload, ct).ConfigureAwait(false);
    }

    private static async Task<int> ReadVarIntAsync(Stream s, CancellationToken ct)
    {
        var result = 0;
        var shift = 0;
        while (true)
        {
            var buf = new byte[1];
            var n = await s.ReadAsync(buf.AsMemory(0, 1), ct).ConfigureAwait(false);
            if (n == 0) return -1;
            var b = buf[0];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) return -1;
        }
        return result;
    }

    private static int ReadVarIntFromStream(Stream s)
    {
        var result = 0;
        var shift = 0;
        while (true)
        {
            var b = s.ReadByte();
            if (b < 0) return -1;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) return -1;
        }
        return result;
    }
}

public sealed record McServerStatus(string Version, int Online, int MaxPlayers)
{
    /// <summary>
    /// 展示文本。
    ///
    /// 注意：服务端玩家数是假数据（服主确认），所以这里不展示在线人数。
    /// </summary>
    public string Display => $"服务器在线 · {Version}";
}