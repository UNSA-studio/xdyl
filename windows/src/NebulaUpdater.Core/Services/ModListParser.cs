using NebulaUpdater.Core.Models;

namespace NebulaUpdater.Core.Services;

/// <summary>
/// file_list.csv 解析。
///
/// 格式（每行）：
///   "./文件名.jar","12M",12345678,md5hex,sha256hex
///
/// 解析策略故意写得宽容：文件名里可能带逗号、引号，所以按引号配对切分，
/// 而不是简单 Split(',')。
/// </summary>
public static class ModListParser
{
    /// <summary>解析整段 CSV 文本</summary>
    public static List<ModInfo> Parse(string csv)
    {
        var result = new List<ModInfo>();
        if (string.IsNullOrWhiteSpace(csv)) return result;

        foreach (var raw in csv.Split('\n'))
        {
            var line = raw.Trim().TrimEnd('\r');
            if (line.Length == 0) continue;
            var item = ParseLine(line);
            if (item != null) result.Add(item);
        }
        return result;
    }

    /// <summary>解析单行；格式不符返回 null</summary>
    public static ModInfo? ParseLine(string line)
    {
        var fields = SplitCsvLine(line);
        if (fields.Count < 5) return null;

        var name = fields[0].Trim();
        if (name.StartsWith("./")) name = name[2..];
        if (name.Length == 0) return null;

        long.TryParse(fields[2].Trim(), out var size);

        return new ModInfo
        {
            FileName = name,
            SizeText = fields[1].Trim(),
            Size = size,
            Md5 = fields[3].Trim().ToLowerInvariant(),
            Sha256 = fields[4].Trim().ToLowerInvariant()
        };
    }

    /// <summary>按引号规则拆分一行 CSV</summary>
    private static List<string> SplitCsvLine(string line)
    {
        var fields = new List<string>(6);
        var sb = new System.Text.StringBuilder();
        var inQuotes = false;

        foreach (var c in line)
        {
            if (c == '"')
            {
                inQuotes = !inQuotes;
                continue;
            }
            if (c == ',' && !inQuotes)
            {
                fields.Add(sb.ToString());
                sb.Clear();
                continue;
            }
            sb.Append(c);
        }
        fields.Add(sb.ToString());
        return fields;
    }

    /// <summary>
    /// 读取内置 CSV。
    ///
    /// 优先用 %USERPROFILE%\.xdyl\file_list.csv（用户可自行替换成自定义列表），
    /// 不存在则回退到随程序打包的 embedded 资源。
    /// </summary>
    public static async Task<string> LoadDefaultCsvAsync(CancellationToken ct = default)
    {
        var overridePath = Path.Combine(UpdaterConstants.AppDataDir, "file_list.csv");
        if (File.Exists(overridePath))
        {
            return await File.ReadAllTextAsync(overridePath, ct).ConfigureAwait(false);
        }

        var asm = typeof(ModListParser).Assembly;
        var resName = asm.GetManifestResourceNames()
            .FirstOrDefault(n => n.EndsWith("file_list.csv", StringComparison.OrdinalIgnoreCase));
        if (resName == null) return "";

        await using var stream = asm.GetManifestResourceStream(resName);
        if (stream == null) return "";
        using var reader = new StreamReader(stream, System.Text.Encoding.UTF8);
        return await reader.ReadToEndAsync(ct).ConfigureAwait(false);
    }
}