using System.Security.Principal;

namespace Butone.Windows;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => ShowFatal(e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            ShowFatal(e.ExceptionObject as Exception ?? new Exception("Неизвестная ошибка"));

        if (!IsAdministrator())
        {
            MessageBox.Show(
                "Для системного VPN нужны права администратора. Запустите приложение ещё раз и подтвердите запрос Windows.",
                "butone VPN",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
            return;
        }

        Application.Run(new MainForm());
    }

    private static bool IsAdministrator()
    {
        using var identity = WindowsIdentity.GetCurrent();
        return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
    }

    private static void ShowFatal(Exception error)
    {
        try
        {
            MessageBox.Show(error.Message, "Ошибка butone VPN", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        catch
        {
            // The process is already shutting down.
        }
    }
}
