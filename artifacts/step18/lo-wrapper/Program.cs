using System;
using System.Diagnostics;
using System.Linq;

internal static class Program
{
    private static int Main(string[] args)
    {
        var start = new ProcessStartInfo
        {
            FileName = @"C:\Program Files\LibreOffice\program\soffice.com",
            Arguments = string.Join(" ", args.Select(Normalize).Select(Quote)),
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };
        using (var process = Process.Start(start))
        {
            process.OutputDataReceived += (_, eventArgs) =>
            {
                if (eventArgs.Data != null) Console.Out.WriteLine(eventArgs.Data);
            };
            process.ErrorDataReceived += (_, eventArgs) =>
            {
                if (eventArgs.Data != null) Console.Error.WriteLine(eventArgs.Data);
            };
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            process.WaitForExit();
            return process.ExitCode;
        }
    }

    private static string Quote(string value)
    {
        return "\"" + value.Replace("\"", "\\\"") + "\"";
    }

    private static string Normalize(string value)
    {
        const string prefix = "-env:UserInstallation=file://";
        if (value.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            var path = value.Substring(prefix.Length).Replace('\\', '/');
            return prefix + "/" + path;
        }
        return value;
    }
}
